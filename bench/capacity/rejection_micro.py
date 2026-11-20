#!/usr/bin/env python3
"""Fork the real Spring rejection path against frozen engine jars, sharing one compiled test fixture."""
import argparse,hashlib,json,os,statistics,subprocess,xml.etree.ElementTree as ET,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--before',type=Path,required=True);p.add_argument('--after',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    module=ROOT/'llmgw-engine/llmgw-engine-webflux'
    xml=module/'target/surefire-reports/TEST-io.github.leohou0430.llmgw.engine.webflux.CapacityAdmissionTest.xml'
    if not xml.exists():raise RuntimeError('First run mvn -pl llmgw-engine/llmgw-engine-webflux -am test -Dtest=CapacityAdmissionTest -Dsurefire.failIfNoSpecifiedTests=false')
    cp=next(x.attrib['value'] for x in ET.parse(xml).getroot().findall('properties/property') if x.attrib['name']=='java.class.path').split(os.pathsep)
    cp=[x for x in cp if Path(x).resolve()!=(module/'target/classes').resolve()]
    classes=out/'classes';classes.mkdir(exist_ok=True)
    source=ROOT/'bench/micro/OverloadAdmissionBench.java';java=str(Path(os.environ['JAVA_HOME'])/'bin/java');javac=str(Path(os.environ['JAVA_HOME'])/'bin/javac')
    subprocess.run([javac,'-cp',os.pathsep.join(cp),'-d',str(classes),str(source)],check=True)
    jars={};manifest={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'fixture_sha256':hashlib.sha256((module/'src/test/java/io/github/leohou0430/llmgw/engine/webflux/CapacityAdmissionTest.java').read_bytes()).hexdigest(),'classpath':cp,'jars':{}}
    for label,jar in [('before',a.before),('after',a.after)]:
        with zipfile.ZipFile(jar) as z:
            name=next(x for x in z.namelist() if x.endswith('/llmgw-engine-webflux-0.5.0.jar'))
            target=out/(label+'-engine.jar');target.write_bytes(z.read(name));jars[label]=target
        manifest['jars'][label]={'app_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'engine_sha256':hashlib.sha256(target.read_bytes()).hexdigest()}
    rows=[]
    for rnd in range(1,4):
        for label in (['before','after'] if rnd%2 else ['after','before']):
            command=[java,'-Xms128m','-Xmx1024m','-cp',os.pathsep.join([str(classes),str(jars[label]),*cp]),'io.github.leohou0430.llmgw.engine.webflux.OverloadAdmissionBench']
            run=subprocess.run(command,capture_output=True,text=True,timeout=120);(out/f'{label}-{rnd}.log').write_text(run.stdout+run.stderr)
            if run.returncode:raise RuntimeError(run.stderr[-3000:])
            row=json.loads(next(line for line in run.stdout.splitlines() if line.startswith('{"operations"')));row.update(label=label,round=rnd)
            expected=1 if label=='after' else row['operations']+row['warmup']+1
            assert row['adapter_calls']==expected,(row,expected)
            rows.append(row);print(json.dumps(row),flush=True)
    report={'manifest':manifest,'rows':rows}
    for label in ['before','after']:
        items=[x for x in rows if x['label']==label]
        report[label]={key:{'median':statistics.median(x[key] for x in items),'min':min(x[key] for x in items),'max':max(x[key] for x in items)} for key in ['bytes_per_rejection','cpu_ns_per_rejection']}
    (out/'result.json').write_text(json.dumps(report,indent=2))
if __name__=='__main__':main()
