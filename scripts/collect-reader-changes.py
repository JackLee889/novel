"""Compare the initial backed-up source and Maven template output, without deleting anything."""
import hashlib,json,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
baseline=json.loads((root/'backups/reader-20261006/baseline-sha256.json').read_text(encoding='utf-8-sig'))
previous={item['path'].replace('\\','/'):item for item in baseline}
paths=list((root/'novel-front/src').rglob('*'))+list((root/'templates/green').rglob('*'))+[root/'novel-front/pom.xml']
paths+=list((root/'tests/reader').rglob('*'))+list((root/'reader-resources').rglob('*'))
paths+=[root/'scripts'/name for name in [
    'prepare-reader-tts.ps1','prepare-reader-icons.ps1','build-reader-validation.ps1',
    'start-reader-validation.ps1','collect-reader-changes.py','summarize-reader-validation.cjs','reader-frontend.override.yaml']]
paths.append(root/'doc/reader-sherpa-validation.md')
changes=[]
for p in paths:
    if not p.is_file(): continue
    relative=p.relative_to(root).as_posix()
    sha=hashlib.sha256(p.read_bytes()).hexdigest()
    old=previous.get(relative)
    if old and old['sha256']==sha: continue
    kind=('package-generated' if relative.startswith('templates/green/') else
        'resource' if relative.startswith('reader-resources/') else
        'validation-and-operations' if relative.startswith(('scripts/','tests/','doc/')) else 'source')
    changes.append({'path':relative,'kind':kind,
        'status':'modified' if old else 'added','beforeSha256':old['sha256'] if old else None,'afterSha256':sha,'bytes':p.stat().st_size})
for relative,old in previous.items():
    if not (root/relative).is_file():changes.append({'path':relative,'status':'missing','beforeSha256':old['sha256']})
out=root/'doc/reader-evidence'
out.mkdir(parents=True,exist_ok=True)
(out/'changed-files.json').write_text(json.dumps(changes,ensure_ascii=False,indent=2),encoding='utf-8')
jar=root/'novel-front/target/novel-front-5.3.3.jar'
result={'jar':str(jar),'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'bytes':jar.stat().st_size}
with zipfile.ZipFile(jar) as z:
    names=z.namelist()
    result['containsReader']=any(n.endswith('/templates/book/reader.html') for n in names)
    result['containsWorker']=any(n.endswith('/javascript/reader/sherpa.worker.js') for n in names)
    result['modelBinariesInJar']=[n for n in names if n.endswith(('.wasm','.data')) and 'sherpa' in n]
    result['testFixtureClassesInJar']=[n for n in names if 'ReaderFixtureApplication' in n]
    resource_root=root/'novel-front/src/main/resources'
    expected_resources=[resource_root/'templates/book/reader.html',resource_root/'static/css/reader.css']
    expected_resources+=list((resource_root/'static/javascript/reader').rglob('*'))+list((resource_root/'static/reader').rglob('*'))
    expected_resources=[p for p in expected_resources if p.is_file()]
    for p in expected_resources:
        entry='BOOT-INF/classes/'+p.relative_to(resource_root).as_posix()
        assert z.read(entry)==p.read_bytes(), 'Packaged resource differs from source: '+entry
    result['readerResourcesVerifiedInJar']=len(expected_resources)
assert result['containsReader'] and result['containsWorker']
assert not result['modelBinariesInJar'] and not result['testFixtureClassesInJar']
archive=root/'novel-front/target/build/novel-front.zip'
result['distributionZip']={'path':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'bytes':archive.stat().st_size}
with zipfile.ZipFile(archive) as z: assert not any(n.endswith('/novel-front.zip') or n=='novel-front.zip' for n in z.namelist())
(out/'release-artifact.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'sourceChanges':sum(c.get('kind')=='source' for c in changes),
 'generatedChanges':sum(c.get('kind')=='package-generated' for c in changes),
 'operationFiles':sum(c.get('kind')=='validation-and-operations' for c in changes),
 'resourceFiles':sum(c.get('kind')=='resource' for c in changes),'jar':result},ensure_ascii=False))
