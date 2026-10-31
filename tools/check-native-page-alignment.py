#!/usr/bin/env python3
"""Reject 64-bit ELF libraries incompatible with 16 KiB pages in APK/AAB/JAR files.

This checks ELF load segments, not runtime behavior or APK zip alignment.
Use zipalign -c -P 16 for APK packaging and a 16 KiB device for runtime validation.
"""

import argparse
from pathlib import Path
import struct
import sys
import zipfile

PAGE_SIZE = 16 * 1024


def inspect_elf(data):
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF library")
    if data[4] == 1:
        return None  # The 16 KiB Android requirement concerns 64-bit ABIs.
    if data[4] != 2 or data[5] not in (1, 2) or len(data) < 64:
        raise ValueError("invalid ELF header")
    endian = "<" if data[5] == 1 else ">"
    offset = struct.unpack_from(endian + "Q", data, 32)[0]
    entry_size, count = struct.unpack_from(endian + "HH", data, 54)
    if entry_size < 56 or offset + count * entry_size > len(data):
        raise ValueError("invalid ELF program-header table")
    alignments = []
    for index in range(count):
        header = struct.unpack_from(endian + "IIQQQQQQ", data, offset + index * entry_size)
        kind, _, file_offset, virtual_address, _, _, _, alignment = header
        if kind != 1:  # PT_LOAD
            continue
        if alignment < PAGE_SIZE or alignment & (alignment - 1):
            raise ValueError(f"PT_LOAD alignment {alignment:#x} is not compatible with 16 KiB pages")
        if file_offset % PAGE_SIZE != virtual_address % PAGE_SIZE:
            raise ValueError("PT_LOAD file offset and virtual address are not congruent at 16 KiB")
        alignments.append(alignment)
    if not alignments:
        raise ValueError("ELF has no loadable segments")
    return min(alignments)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", type=Path, nargs="+")
    args = parser.parse_args()
    failed = False
    for path in args.archives:
        checked = 0
        try:
            with zipfile.ZipFile(path) as archive:
                for member in archive.infolist():
                    if not member.filename.endswith(".so"):
                        continue
                    try:
                        alignment = inspect_elf(archive.read(member))
                        if alignment is not None:
                            checked += 1
                            print(f"PASS {path.name}: {member.filename}: minimum PT_LOAD alignment {alignment:#x}")
                    except (ValueError, struct.error) as error:
                        failed = True
                        print(f"FAIL {path.name}: {member.filename}: {error}", file=sys.stderr)
            if not checked:
                failed = True
                print(f"FAIL {path}: no compatible 64-bit ELF libraries found", file=sys.stderr)
        except (OSError, zipfile.BadZipFile) as error:
            failed = True
            print(f"FAIL {path}: {error}", file=sys.stderr)
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
