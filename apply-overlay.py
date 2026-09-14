import hashlib
import io
import json
import subprocess
import sys
import zipfile
from pathlib import Path

source = Path(sys.argv[1]).resolve()
parts = sorted(Path(__file__).parent.glob('nigun-overlay.zip.part-*'))
archive = b''.join(part.read_bytes() for part in parts)
if hashlib.sha256(archive).hexdigest() != '7975534757ae3a3ce6ad66694481d0e6c3dcbfd1143b4e2a6a20f33d1a57f058':
    raise SystemExit('Nigun overlay archive missing or corrupt')
with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
    manifest = json.loads(bundle.read('nigun-overlay-manifest.json'))
    commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if commit != manifest['baseCommit']:
        raise SystemExit('Unexpected Metrolist base commit')
    writes = []
    for item in manifest['files']:
        target = (source / item['path']).resolve()
        if not target.is_relative_to(source) or '.git' in target.relative_to(source).parts:
            raise SystemExit('Unsafe overlay path')
        content = bundle.read(item['path'])
        if hashlib.sha256(content).hexdigest() != item['sha256']:
            raise SystemExit('Overlay checksum mismatch: ' + item['path'])
        writes.append((target, content))
    deletions = []
    for relative in manifest['deleted']:
        target = (source / relative).resolve()
        if not target.is_relative_to(source) or '.git' in target.relative_to(source).parts:
            raise SystemExit('Unsafe deletion path')
        deletions.append(target)
    for target, content in writes:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    for target in deletions:
        target.unlink(missing_ok=True)
print('Applied and verified', len(writes), 'Nigun source files')

# Readable changes are applied only after the pinned archive and base commit are verified.
override_root = Path(__file__).resolve().parent / "overrides"
overrides = []
if override_root.is_dir():
    for entry in sorted(override_root.rglob("*")):
        if entry.is_symlink():
            raise SystemExit("Symlinks are not supported in source overrides")
        if not entry.is_file():
            continue
        relative = entry.relative_to(override_root)
        target = (source / relative).resolve()
        if not target.is_relative_to(source) or ".git" in relative.parts:
            raise SystemExit("Unsafe source override path")
        overrides.append((target, entry.read_bytes()))
    for target, content in overrides:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
print("Applied", len(overrides), "reviewable source overrides")
