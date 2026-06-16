r"""
script-copier.py
Recursively scans the current workspace paths,
reads every source file, and writes their contents into fundamentals.md
with clear file-path headers and fenced code blocks.

Usage:
    python script-copier.py              # default source & output
    python script-copier.py --dry-run    # preview what would be copied
"""

import os
import sys
import argparse
from pathlib import Path
from datetime import datetime

# Console encoding fix
import io
if sys.stdout.encoding.lower() != 'utf-8':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

# ── Configuration ──────────────────────────────────────────────────────────────
# Updated to match the current Linux workspace (/root/fundamentals)
SOURCE_DIR = [
    Path("/root/fiidii/backend"),
    Path("/root/fiidii/storage"),
    Path("/root/fiidii/data/config"),
    Path("/root/fiidii/logs"),
]

# Directories to exclude from scanning
EXCLUDE_DIRS = {
    Path("/root/fiidii/backend/src/test"),
    Path("/root/fiidii/backend/target"),
}
OUTPUT_FILE = Path("/root/fiidii/fiidii.md")

# Map file extensions → markdown code-fence language tags
EXTENSION_LANG = {
    ".java":       "java",
    ".py":         "python",
    ".js":         "javascript",
    ".ts":         "typescript",
    ".jsx":        "jsx",
    ".tsx":        "tsx",
    ".json":       "json",
    ".xml":        "xml",
    ".yaml":       "yaml",
    ".yml":        "yaml",
    ".properties": "properties",
    ".sql":        "sql",
    ".sh":         "bash",
    ".bat":        "batch",
    ".gradle":     "groovy",
    ".kt":         "kotlin",
    ".scala":      "scala",
    ".proto":      "protobuf",
    ".html":       "html",
    ".css":        "css",
    ".md":         "markdown",
    ".txt":        "text",
    ".cfg":        "ini",
    ".ini":        "ini",
    ".toml":       "toml",

    ".jsonl":         "jsonl",
}

# Extensions to skip (binary / non-script files)
SKIP_EXTENSIONS = {".jar", ".class", ".war", ".ear", ".zip", ".gz", ".tar",
                   ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg",
                   ".exe", ".dll", ".so", ".dylib", ".pdf", ".doc", ".docx"}


def collect_files(sources: list[Path]) -> list[tuple[Path, Path]]:
    """Recursively collect all script/source files, sorted by path."""
    files = []
    for source_dir in sources:
        if not source_dir.exists():
            print(f"❌ Source directory not found: {source_dir}")
            continue
        for root, dirs, filenames in os.walk(source_dir):
            # Prune excluded directories in-place so os.walk skips them
            dirs[:] = [d for d in dirs if Path(root, d).resolve() not in
                       {p.resolve() for p in EXCLUDE_DIRS}]
            for fname in filenames:
                fpath = Path(root) / fname
                if fpath.suffix.lower() in SKIP_EXTENSIONS:
                    continue
                files.append((fpath, source_dir))
    files.sort(key=lambda x: x[0])
    return files


def lang_tag(filepath: Path) -> str:
    """Return the markdown language tag for a file extension."""
    return EXTENSION_LANG.get(filepath.suffix.lower(), "")


def build_markdown(files: list[tuple[Path, Path]]) -> str:
    """Build the full markdown string with all file contents."""
    lines: list[str] = []

    for fpath, source_dir in files:
        rel = fpath.relative_to(source_dir.parent if source_dir.name in ["java", "resources", "upstox"] else source_dir)
        tag = lang_tag(fpath)

        try:
            content = fpath.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            # Skip files that aren't valid UTF-8 (likely binary)
            print(f"  ⚠ Skipped (binary): {fpath}")
            continue

        lines.append(f"```{tag}")
        lines.append(f"// File: {rel}")
        lines.append(content.rstrip())
        lines.append("```")
        lines.append("")  # blank line between files

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Copy source scripts into a markdown file.")
    parser.add_argument("--dry-run", action="store_true",
                        help="List files that would be copied without writing.")
    parser.add_argument("--source", type=Path, nargs="*", default=SOURCE_DIR,
                        help="Source directory to scan.")
    parser.add_argument("--output", type=Path, default=OUTPUT_FILE,
                        help="Output markdown file.")
    args = parser.parse_args()

    sources = args.source
    if not isinstance(sources, list):
        sources = [sources]
    output = args.output

    print(f"📂 Scanning: {[str(s) for s in sources]}")
    files = collect_files(sources)
    print(f"📄 Found {len(files)} file(s)\n")

    if not files:
        print("Nothing to copy.")
        return

    # Print summary table
    print(f"{'#':<4} {'Path':<70} {'Size':>8}")
    print("─" * 84)
    total_bytes = 0
    for i, (f, src) in enumerate(files, 1):
        rel = f.relative_to(src.parent if src.name in ["java", "resources", "upstox"] else src)
        size = f.stat().st_size
        total_bytes += size
        print(f"{i:<4} {str(rel):<70} {size:>8}")
    print("─" * 84)
    print(f"     Total: {len(files)} files, {total_bytes:,} bytes\n")

    if args.dry_run:
        print("🔍 Dry run — no file written.")
        return

    # Build markdown and write
    md = build_markdown(files)
    output.write_text(md, encoding="utf-8")
    print(f"✅ Written to: {output}")
    print(f"   Output size: {output.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
