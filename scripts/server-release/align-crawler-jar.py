"""Reproduce the verified crawler-specific SEO class family in the release JAR.

The frontend retains the current common-module implementation. The crawler
uses its own recovered source and the narrowly pinned historical compatibility
step. No server connection or database access is performed by this script.
"""
from pathlib import Path
from io import BytesIO
from zipfile import ZipFile
import argparse
import json
import os
import subprocess
import sys

FAMILY='com/java2nb/novel/seo/SeoRepository'
TOP=FAMILY+'.class'

def belongs(name):
    return name==TOP or name.startswith(FAMILY+'$') and name.endswith('.class')

def filtered_common(data):
    output=BytesIO()
    with ZipFile(BytesIO(data)) as source, ZipFile(output,'w') as target:
        for info in source.infolist():
            if not belongs(info.filename): target.writestr(info,source.read(info))
    return output.getvalue()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-root',type=Path,default=Path(__file__).resolve().parents[2])
    parser.add_argument('--java-home',type=Path,default=os.environ.get('JAVA_HOME'))
    args=parser.parse_args()
    root=args.project_root.resolve()
    target=root/'novel-crawl/target'
    jars=[p for p in target.glob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar'))]
    if len(jars)!=1: raise SystemExit('Expected exactly one packaged crawler JAR in '+str(target))
    jar=jars[0]
    compiled=target/'classes'/TOP
    patched=target/'server-aligned'/TOP
    patched.parent.mkdir(parents=True,exist_ok=True)
    helper=Path(__file__).with_name('restore_crawler_seo_shape.py')
    command=[sys.executable,str(helper),str(compiled),str(patched),'--tools-dir',str(target/'server-release-tools')]
    if args.java_home: command+=['--java-home',str(args.java_home)]
    subprocess.run(command,check=True)
    replacement=patched.read_bytes()
    temporary=jar.with_suffix('.server-aligned.tmp')
    seen_common=0
    seen_top=0
    with ZipFile(jar) as source, ZipFile(temporary,'w') as output:
        for info in source.infolist():
            data=source.read(info)
            if info.filename.startswith('BOOT-INF/lib/novel-common-') and info.filename.endswith('.jar'):
                data=filtered_common(data); seen_common+=1
            elif info.filename=='BOOT-INF/classes/'+TOP:
                data=replacement; seen_top+=1
            output.writestr(info,data)
    if seen_common!=1 or seen_top!=1:
        raise SystemExit('Unexpected JAR layout; original JAR has not been replaced')
    with ZipFile(temporary) as check:
        bad=check.testzip()
        if bad: raise SystemExit('JAR integrity error: '+bad)
    os.replace(temporary,jar)
    print(json.dumps({'crawler_jar':str(jar),'server_seo_shape':'restored','shared_frontend_implementation':'unchanged','database_accessed':False},indent=2))

if __name__=='__main__': main()
