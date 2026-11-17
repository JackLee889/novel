"""Restore the pinned public reader dependency; no database or credentials used."""
from pathlib import Path
import hashlib
import json
import tarfile
import urllib.request

ROOT=Path(__file__).resolve().parents[1]

def digest(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()

def main():
    for manifest_path in sorted((ROOT/'reader-resources').glob('*/asset-manifest.json')):
        manifest=json.loads(manifest_path.read_text(encoding='utf-8-sig'))
        directory=manifest_path.parent
        missing=[]
        for item in manifest['assets']:
            name=item['name']
            if Path(name).name!=name or '\\' in name: raise ValueError('Unsafe asset name')
            target=directory/name
            if target.exists():
                if digest(target)!=item['sha256']: raise ValueError('Existing reader asset differs: '+str(target))
            else: missing.append(item)
        if not missing:
            print(directory.name+': all assets already verified'); continue
        if not manifest['source'].startswith('https://github.com/k2-fsa/sherpa-onnx/releases/download/'):
            raise ValueError('Expected the pinned official release URL')
        cache=ROOT/'dist/dependency-cache'; cache.mkdir(parents=True,exist_ok=True)
        archive=cache/(manifest['archiveSha256']+'.tar.bz2')
        if not archive.exists(): urllib.request.urlretrieve(manifest['source'],archive)
        if digest(archive)!=manifest['archiveSha256']: raise ValueError('Official archive SHA256 mismatch')
        with tarfile.open(archive,'r:bz2') as tar:
            members=tar.getmembers()
            for item in missing:
                candidates=[m for m in members if m.isfile() and Path(m.name).name==item['name']]
                if len(candidates)!=1: raise ValueError('Expected one asset: '+item['name'])
                target=directory/item['name']
                temporary=target.with_suffix(target.suffix+'.download')
                if temporary.exists(): raise ValueError('Partial file already exists: '+str(temporary))
                with tar.extractfile(candidates[0]) as source, temporary.open('xb') as output:
                    while block:=source.read(1024*1024): output.write(block)
                if temporary.stat().st_size!=int(item['bytes']) or digest(temporary)!=item['sha256']:
                    raise ValueError('Reader asset verification failed: '+item['name'])
                temporary.rename(target)
                print(item['name']+': SHA256 verified')

if __name__=='__main__': main()
