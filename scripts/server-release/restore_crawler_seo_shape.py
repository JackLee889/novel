"""Build-only compatibility step for the server-authoritative crawler release.

Requires an installed JDK 21; uses its bundled ASM implementation, without
downloading packages. Input is the freshly compiled recovered crawler override.
All annotations, fields and business instructions remain those of that source.
"""
from pathlib import Path
import argparse
import os
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input_class', type=Path)
    parser.add_argument('output_class', type=Path)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--tools-dir', type=Path)
    args = parser.parse_args()
    if not args.java_home:
        parser.error('--java-home must identify the installed JDK 21')
    java_home = Path(args.java_home)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = java_home / 'bin' / ('javac' + suffix)
    java = java_home / 'bin' / ('java' + suffix)
    if not javac.is_file() or not java.is_file():
        parser.error('The selected JDK does not provide javac and java')
    tools = args.tools_dir or args.output_class.parent / '.server-release-tools'
    tools.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).with_name('RestoreCrawlerSeoShape.java')
    exports = [f'--add-exports=java.base/jdk.internal.org.objectweb.asm{x}=ALL-UNNAMED' for x in ['', '.tree']]
    subprocess.run([str(javac), *exports, '-d', str(tools), str(source)], check=True)
    subprocess.run([str(java), *exports, '-cp', str(tools), 'RestoreCrawlerSeoShape', str(args.input_class), str(args.output_class)], check=True)


if __name__ == '__main__':
    main()
