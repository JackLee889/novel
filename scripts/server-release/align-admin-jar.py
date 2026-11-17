"""Package the separate admin source variant to match the deployed image."""
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile
import argparse,json,os

FAMILY='com/java2nb/novel/seo/SeoRepository'
IO='com/java2nb/novel/core/io/'
EXCLUDES={IO+x for x in ('BackgroundIo.class','BackgroundIoAspect.class','BackgroundIoGate.class','BackgroundIoGate$BusyException.class','BackgroundIoGate$Permit.class')}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-root',type=Path,default=Path(__file__).resolve().parents[2])
    args=parser.parse_args()
    target=args.project_root.resolve()/'novel-admin/target'
    jars=[p for p in target.glob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar'))]
    if len(jars)!=1: raise SystemExit('Expected one packaged admin JAR')
    jar=jars[0]; temporary=jar.with_suffix('.server-aligned.tmp')
    common_count=0; own_family_count=0
    with ZipFile(jar) as source,ZipFile(temporary,'w') as output:
        for info in source.infolist():
            data=source.read(info)
            if info.filename.startswith('BOOT-INF/lib/novel-common-') and info.filename.endswith('.jar'):
                common_count+=1; buffer=BytesIO()
                with ZipFile(BytesIO(data)) as common,ZipFile(buffer,'w') as filtered:
                    for member in common.infolist():
                        name=member.filename
                        if name in EXCLUDES or name==FAMILY+'.class' or name.startswith(FAMILY+'$') and name.endswith('.class'): continue
                        filtered.writestr(member,common.read(member))
                data=buffer.getvalue()
            if info.filename.startswith('BOOT-INF/classes/'+FAMILY) and info.filename.endswith('.class'): own_family_count+=1
            output.writestr(info,data)
    if common_count!=1 or own_family_count!=8:
        raise SystemExit('Expected one shared library and eight admin-specific SEO class files; original JAR preserved')
    with ZipFile(temporary) as check:
        bad=check.testzip()
        if bad: raise SystemExit('JAR integrity failure: '+bad)
    os.replace(temporary,jar)
    print(json.dumps({'admin_jar':str(jar),'admin_source_variant':'preserved','database_accessed':False},indent=2))

if __name__=='__main__': main()
