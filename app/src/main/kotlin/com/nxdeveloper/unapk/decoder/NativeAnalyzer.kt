package com.nxdeveloper.unapk.decoder

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeAnalyzer {

    fun analyze(libRoot: File, outputDirectory: File, onProgress: (Float) -> Unit): NativeAnalysisReport {
        val warnings = mutableListOf<String>()
        if (!libRoot.exists() || !libRoot.isDirectory) {
            return NativeAnalysisReport(
                outputDirectory = outputDirectory,
                analyzedFiles = 0,
                discoveredFiles = 0,
                architectures = emptyList(),
                warnings = warnings,
                fatalError = null
            )
        }

        val soFiles = mutableListOf<File>()
        collectSoFiles(libRoot, soFiles)
        if (soFiles.isEmpty()) {
            return NativeAnalysisReport(
                outputDirectory = outputDirectory,
                analyzedFiles = 0,
                discoveredFiles = 0,
                architectures = emptyList(),
                warnings = warnings,
                fatalError = null
            )
        }

        outputDirectory.mkdirs()
        val perAbiSummaries = LinkedHashMap<String, StringBuilder>()
        val architectures = mutableSetOf<String>()
        var analyzed = 0

        soFiles.forEachIndexed { index, soFile ->
            val abi = soFile.parentFile?.name ?: "unknown"
            try {
                val report = ElfReader.read(soFile)
                val target = File(outputDirectory, "$abi/${soFile.name}.txt")
                target.parentFile?.mkdirs()
                target.writeText(report.fullText)
                architectures.add(report.architecture)

                val summary = perAbiSummaries.getOrPut(abi) { StringBuilder() }
                summary.append("=== ").append(soFile.name).append(" ===\n")
                summary.append(report.shortText).append("\n\n")

                val collectedStrings = extractStrings(soFile)
                if (collectedStrings.isNotEmpty()) {
                    val stringsFile = File(outputDirectory, "$abi/${soFile.name}.strings.txt")
                    stringsFile.writeText(collectedStrings.joinToString("\n"))
                }

                analyzed++
            } catch (oom: OutOfMemoryError) {
                warnings.add("native analysis ran out of memory on ${soFile.name}: ${oom.message}")
            } catch (error: Throwable) {
                warnings.add("could not analyze ${soFile.name}: ${error.message}")
            }
            onProgress((index + 1).toFloat() / soFiles.size.toFloat())
        }

        for ((abi, builder) in perAbiSummaries) {
            val target = File(outputDirectory, "$abi/_summary.txt")
            target.writeText(builder.toString())
        }

        val indexFile = File(outputDirectory, "index.txt")
        val indexText = buildString {
            append("Native libraries scanned: ").append(soFiles.size).append('\n')
            append("Successfully parsed: ").append(analyzed).append('\n')
            append("Architectures detected: ").append(architectures.joinToString(", ")).append('\n')
            append("\nFile tree:\n")
            for (soFile in soFiles) {
                val rel = soFile.absolutePath.removePrefix(libRoot.absolutePath).trimStart(File.separatorChar)
                append("  ").append(rel)
                    .append(" (").append(humanSize(soFile.length())).append(")\n")
            }
        }
        indexFile.writeText(indexText)

        return NativeAnalysisReport(
            outputDirectory = outputDirectory,
            analyzedFiles = analyzed,
            discoveredFiles = soFiles.size,
            architectures = architectures.toList(),
            warnings = warnings,
            fatalError = null
        )
    }

    private fun collectSoFiles(root: File, into: MutableList<File>) {
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else if (child.name.endsWith(".so")) {
                    into.add(child)
                }
            }
        }
    }

    private fun extractStrings(file: File): List<String> {
        val results = mutableListOf<String>()
        val bytes = try {
            file.readBytes()
        } catch (oom: OutOfMemoryError) {
            return results
        } catch (error: Throwable) {
            return results
        }
        val current = StringBuilder()
        for (raw in bytes) {
            val value = raw.toInt() and 0xFF
            if (value in 0x20..0x7E || value == 0x09) {
                current.append(value.toChar())
            } else {
                if (current.length >= MIN_STRING_LENGTH) {
                    results.add(current.toString())
                }
                current.setLength(0)
            }
            if (results.size > MAX_STRINGS) {
                break
            }
        }
        if (current.length >= MIN_STRING_LENGTH) {
            results.add(current.toString())
        }
        return results
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        }
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format("%.2f %s", value, units[unitIndex])
    }

    private companion object {
        const val MIN_STRING_LENGTH = 6
        const val MAX_STRINGS = 50000
    }
}

data class NativeAnalysisReport(
    val outputDirectory: File,
    val analyzedFiles: Int,
    val discoveredFiles: Int,
    val architectures: List<String>,
    val warnings: List<String>,
    val fatalError: Throwable?
)

internal data class ElfDescription(
    val architecture: String,
    val fullText: String,
    val shortText: String
)

internal object ElfReader {

    private const val ELF_MAGIC_0 = 0x7F.toByte()
    private const val ELF_MAGIC_1 = 'E'.code.toByte()
    private const val ELF_MAGIC_2 = 'L'.code.toByte()
    private const val ELF_MAGIC_3 = 'F'.code.toByte()

    private const val SHT_NULL = 0
    private const val SHT_PROGBITS = 1
    private const val SHT_SYMTAB = 2
    private const val SHT_STRTAB = 3
    private const val SHT_RELA = 4
    private const val SHT_DYNAMIC = 6
    private const val SHT_NOTE = 7
    private const val SHT_NOBITS = 8
    private const val SHT_REL = 9
    private const val SHT_DYNSYM = 11

    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_SONAME = 14L
    private const val DT_RPATH = 15L
    private const val DT_RUNPATH = 29L

    fun read(file: File): ElfDescription {
        RandomAccessFile(file, "r").use { raf ->
            val header = readBytes(raf, 0L, 64)
            require(header.size >= 16) { "file too small" }
            require(
                header[0] == ELF_MAGIC_0 && header[1] == ELF_MAGIC_1 &&
                    header[2] == ELF_MAGIC_2 && header[3] == ELF_MAGIC_3
            ) { "not an ELF file" }

            val eiClass = header[4].toInt() and 0xFF
            val eiData = header[5].toInt() and 0xFF
            val is64 = eiClass == 2
            val byteOrder = if (eiData == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

            val ident = readBytes(raf, 0L, if (is64) 64 else 52)
            val ib = ByteBuffer.wrap(ident).order(byteOrder)
            ib.position(16)
            val eType = ib.short.toInt() and 0xFFFF
            val eMachine = ib.short.toInt() and 0xFFFF
            ib.int
            val eEntry = if (is64) ib.long else ib.int.toLong() and 0xFFFFFFFFL
            val ePhoff = if (is64) ib.long else ib.int.toLong() and 0xFFFFFFFFL
            val eShoff = if (is64) ib.long else ib.int.toLong() and 0xFFFFFFFFL
            ib.int
            ib.short
            val ePhentsize = ib.short.toInt() and 0xFFFF
            val ePhnum = ib.short.toInt() and 0xFFFF
            val eShentsize = ib.short.toInt() and 0xFFFF
            val eShnum = ib.short.toInt() and 0xFFFF
            val eShstrndx = ib.short.toInt() and 0xFFFF

            val sections = readSections(raf, byteOrder, is64, eShoff, eShnum, eShentsize)
            val nameTable = readSectionNameTable(raf, sections, eShstrndx)
            applySectionNames(sections, nameTable)

            val needed = mutableListOf<String>()
            val soName = StringBuilder()
            val rpath = StringBuilder()
            val dynamicSection = sections.firstOrNull { it.type == SHT_DYNAMIC }
            val dynStr = sections.firstOrNull { it.type == SHT_STRTAB && it.nameValue == ".dynstr" }
            if (dynamicSection != null && dynStr != null) {
                readDynamic(raf, dynamicSection, dynStr, byteOrder, is64, needed, soName, rpath)
            }

            val exported = mutableListOf<String>()
            val imported = mutableListOf<String>()
            val dynSym = sections.firstOrNull { it.type == SHT_DYNSYM }
            if (dynSym != null && dynStr != null) {
                readSymbols(raf, dynSym, dynStr, byteOrder, is64, exported, imported)
            }
            val symTab = sections.firstOrNull { it.type == SHT_SYMTAB }
            val strTab = sections.firstOrNull { it.type == SHT_STRTAB && it.nameValue == ".strtab" }
            if (symTab != null && strTab != null) {
                readSymbols(raf, symTab, strTab, byteOrder, is64, exported, imported)
            }

            val architecture = machineName(eMachine)
            val bitness = if (is64) "64-bit" else "32-bit"
            val endian = if (eiData == 1) "little-endian" else "big-endian"
            val typeName = objectTypeName(eType)

            val builder = StringBuilder()
            builder.append("File: ").append(file.name).append('\n')
            builder.append("Size: ").append(humanFileSize(file.length())).append('\n')
            builder.append("Class: ").append(bitness).append('\n')
            builder.append("Endian: ").append(endian).append('\n')
            builder.append("Architecture: ").append(architecture).append('\n')
            builder.append("Object type: ").append(typeName).append('\n')
            builder.append("Entry point: 0x").append(java.lang.Long.toHexString(eEntry)).append('\n')
            builder.append("Program header count: ").append(ePhnum).append('\n')
            builder.append("Section count: ").append(eShnum).append('\n')
            if (soName.isNotEmpty()) {
                builder.append("SONAME: ").append(soName).append('\n')
            }
            if (rpath.isNotEmpty()) {
                builder.append("RPATH/RUNPATH: ").append(rpath).append('\n')
            }
            builder.append('\n')

            if (needed.isNotEmpty()) {
                builder.append("Dynamically linked libraries (DT_NEEDED):\n")
                for (lib in needed) {
                    builder.append("  - ").append(lib).append('\n')
                }
                builder.append('\n')
            }

            if (sections.isNotEmpty()) {
                builder.append("Sections:\n")
                for (sec in sections) {
                    if (sec.nameValue.isBlank()) {
                        continue
                    }
                    builder.append("  ").append(sec.nameValue.padEnd(24))
                        .append(" type=").append(sectionTypeName(sec.type).padEnd(12))
                        .append(" size=").append(humanFileSize(sec.size))
                        .append(" addr=0x").append(java.lang.Long.toHexString(sec.address))
                        .append('\n')
                }
                builder.append('\n')
            }

            if (exported.isNotEmpty()) {
                builder.append("Exported symbols (").append(exported.size).append("):\n")
                for (name in exported) {
                    builder.append("  ").append(name).append('\n')
                }
                builder.append('\n')
            }
            if (imported.isNotEmpty()) {
                builder.append("Imported / undefined symbols (").append(imported.size).append("):\n")
                for (name in imported) {
                    builder.append("  ").append(name).append('\n')
                }
                builder.append('\n')
            }

            val short = StringBuilder()
            short.append("Architecture: ").append(architecture)
                .append(" (").append(bitness).append(", ").append(endian).append(")\n")
            short.append("Type: ").append(typeName).append('\n')
            short.append("NEEDED: ").append(if (needed.isEmpty()) "none" else needed.joinToString(", ")).append('\n')
            short.append("Exported symbols: ").append(exported.size)
                .append(", Imports: ").append(imported.size).append('\n')

            return ElfDescription(
                architecture = architecture,
                fullText = builder.toString(),
                shortText = short.toString()
            )
        }
    }

    private fun readSections(
        raf: RandomAccessFile,
        order: ByteOrder,
        is64: Boolean,
        offset: Long,
        count: Int,
        entrySize: Int
    ): List<ElfSection> {
        if (count == 0 || offset == 0L || entrySize == 0) {
            return emptyList()
        }
        val total = count.toLong() * entrySize.toLong()
        val data = readBytes(raf, offset, total.toInt())
        val sections = mutableListOf<ElfSection>()
        for (index in 0 until count) {
            val base = index * entrySize
            val bb = ByteBuffer.wrap(data, base, entrySize).slice().order(order)
            val nameIndex = bb.int and 0x7FFFFFFF
            val typeValue = bb.int
            if (is64) {
                bb.long
                bb.long
                val secOffset = bb.long
                val secSize = bb.long
                val secLink = bb.int
                val secInfo = bb.int
                bb.long
                val secEntSize = bb.long
                sections.add(
                    ElfSection(
                        nameIndex = nameIndex,
                        nameValue = "",
                        type = typeValue,
                        address = 0L,
                        offset = secOffset,
                        size = secSize,
                        link = secLink,
                        info = secInfo,
                        entrySize = secEntSize
                    )
                )
            } else {
                bb.int
                val secAddr = bb.int.toLong() and 0xFFFFFFFFL
                val secOffset = bb.int.toLong() and 0xFFFFFFFFL
                val secSize = bb.int.toLong() and 0xFFFFFFFFL
                val secLink = bb.int
                val secInfo = bb.int
                bb.int
                val secEntSize = bb.int.toLong() and 0xFFFFFFFFL
                sections.add(
                    ElfSection(
                        nameIndex = nameIndex,
                        nameValue = "",
                        type = typeValue,
                        address = secAddr,
                        offset = secOffset,
                        size = secSize,
                        link = secLink,
                        info = secInfo,
                        entrySize = secEntSize
                    )
                )
            }
        }
        return sections
    }

    private fun readSectionNameTable(raf: RandomAccessFile, sections: List<ElfSection>, stringTableIndex: Int): ByteArray? {
        if (stringTableIndex == 0 || stringTableIndex >= sections.size) {
            return null
        }
        val section = sections[stringTableIndex]
        if (section.size <= 0L) {
            return null
        }
        return readBytes(raf, section.offset, section.size.toInt().coerceAtMost(MAX_SECTION_SIZE))
    }

    private fun applySectionNames(sections: List<ElfSection>, table: ByteArray?) {
        if (table == null) {
            return
        }
        for (i in sections.indices) {
            val section = sections[i]
            val name = readCString(table, section.nameIndex)
            (sections as MutableList)[i] = section.copy(nameValue = name)
        }
    }

    private fun readDynamic(
        raf: RandomAccessFile,
        dynamic: ElfSection,
        dynStr: ElfSection,
        order: ByteOrder,
        is64: Boolean,
        needed: MutableList<String>,
        soName: StringBuilder,
        rpath: StringBuilder
    ) {
        if (dynamic.size <= 0L) {
            return
        }
        val data = readBytes(raf, dynamic.offset, dynamic.size.toInt().coerceAtMost(MAX_SECTION_SIZE))
        val strData = readBytes(raf, dynStr.offset, dynStr.size.toInt().coerceAtMost(MAX_SECTION_SIZE))
        val entrySize = if (is64) 16 else 8
        val count = data.size / entrySize
        for (index in 0 until count) {
            val bb = ByteBuffer.wrap(data, index * entrySize, entrySize).slice().order(order)
            val tag = if (is64) bb.long else bb.int.toLong()
            val value = if (is64) bb.long else bb.int.toLong() and 0xFFFFFFFFL
            when (tag) {
                DT_NULL -> return
                DT_NEEDED -> {
                    val name = readCString(strData, value.toInt())
                    if (name.isNotEmpty()) {
                        needed.add(name)
                    }
                }
                DT_SONAME -> {
                    soName.append(readCString(strData, value.toInt()))
                }
                DT_RPATH, DT_RUNPATH -> {
                    if (rpath.isNotEmpty()) {
                        rpath.append(';')
                    }
                    rpath.append(readCString(strData, value.toInt()))
                }
            }
        }
    }

    private fun readSymbols(
        raf: RandomAccessFile,
        symSection: ElfSection,
        strSection: ElfSection,
        order: ByteOrder,
        is64: Boolean,
        exported: MutableList<String>,
        imported: MutableList<String>
    ) {
        if (symSection.size <= 0L || strSection.size <= 0L) {
            return
        }
        val symEntrySize = if (is64) 24 else 16
        val expectedSize = if (symSection.entrySize > 0L) symSection.entrySize.toInt() else symEntrySize
        val data = readBytes(raf, symSection.offset, symSection.size.toInt().coerceAtMost(MAX_SECTION_SIZE))
        val strData = readBytes(raf, strSection.offset, strSection.size.toInt().coerceAtMost(MAX_SECTION_SIZE))
        val count = data.size / expectedSize
        for (index in 0 until count) {
            val base = index * expectedSize
            val bb = ByteBuffer.wrap(data, base, expectedSize).slice().order(order)
            val nameIndex: Int
            val info: Int
            val shndx: Int
            if (is64) {
                nameIndex = bb.int
                info = bb.get().toInt() and 0xFF
                bb.get()
                shndx = bb.short.toInt() and 0xFFFF
            } else {
                nameIndex = bb.int
                bb.int
                bb.int
                info = bb.get().toInt() and 0xFF
                bb.get()
                shndx = bb.short.toInt() and 0xFFFF
            }
            val name = readCString(strData, nameIndex)
            if (name.isBlank()) {
                continue
            }
            val binding = (info ushr 4) and 0xF
            val symType = info and 0xF
            if (symType == 3 || symType == 4) {
                continue
            }
            if (binding != 1 && binding != 2) {
                continue
            }
            if (shndx == 0) {
                if (!imported.contains(name)) {
                    imported.add(name)
                }
            } else {
                if (!exported.contains(name)) {
                    exported.add(name)
                }
            }
            if (exported.size + imported.size > MAX_SYMBOLS) {
                break
            }
        }
    }

    private fun readCString(table: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= table.size) {
            return ""
        }
        val end = (offset until table.size).firstOrNull { table[it] == 0.toByte() } ?: table.size
        return String(table, offset, end - offset)
    }

    private fun readBytes(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
        if (length <= 0) {
            return ByteArray(0)
        }
        val actualLength = length.coerceAtMost((raf.length() - offset).toInt().coerceAtLeast(0))
        if (actualLength <= 0) {
            return ByteArray(0)
        }
        raf.seek(offset)
        val buffer = ByteArray(actualLength)
        var read = 0
        while (read < actualLength) {
            val r = raf.read(buffer, read, actualLength - read)
            if (r <= 0) {
                break
            }
            read += r
        }
        return if (read == actualLength) buffer else buffer.copyOf(read)
    }

    private fun objectTypeName(value: Int): String {
        return when (value) {
            0 -> "ET_NONE"
            1 -> "ET_REL (relocatable)"
            2 -> "ET_EXEC (executable)"
            3 -> "ET_DYN (shared object / PIE)"
            4 -> "ET_CORE"
            else -> "type 0x${Integer.toHexString(value)}"
        }
    }

    private fun machineName(value: Int): String {
        return when (value) {
            3 -> "x86 (i386)"
            8 -> "MIPS"
            20 -> "PowerPC"
            21 -> "PowerPC64"
            40 -> "ARM (armv7 / armeabi-v7a)"
            62 -> "x86_64"
            183 -> "AArch64 (arm64-v8a)"
            243 -> "RISC-V"
            else -> "machine 0x${Integer.toHexString(value)}"
        }
    }

    private fun sectionTypeName(value: Int): String {
        return when (value) {
            SHT_NULL -> "NULL"
            SHT_PROGBITS -> "PROGBITS"
            SHT_SYMTAB -> "SYMTAB"
            SHT_STRTAB -> "STRTAB"
            SHT_RELA -> "RELA"
            SHT_DYNAMIC -> "DYNAMIC"
            SHT_NOTE -> "NOTE"
            SHT_NOBITS -> "NOBITS"
            SHT_REL -> "REL"
            SHT_DYNSYM -> "DYNSYM"
            else -> "T_0x${Integer.toHexString(value)}"
        }
    }

    private fun humanFileSize(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        }
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format("%.2f %s", value, units[unitIndex])
    }

    private const val MAX_SECTION_SIZE = 64 * 1024 * 1024
    private const val MAX_SYMBOLS = 200000
}

internal data class ElfSection(
    val nameIndex: Int,
    val nameValue: String,
    val type: Int,
    val address: Long,
    val offset: Long,
    val size: Long,
    val link: Int,
    val info: Int,
    val entrySize: Long
)
