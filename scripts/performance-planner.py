"""Compile and compare pure planners with identical 20/100/500-task input, bounded JVMs."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
report = {'baseline':'ca4cb44','repeats':7,'jvmHeap':'512m','cases':{}}
for label in ('before','after'):
    directory = ROOT / '.tools/planner-benchmark' / label
    source = directory / 'src/cn/dormlink/dispatch/RoutePlanner.java'
    source.parent.mkdir(parents=True,exist_ok=True)
    classes = directory / 'classes'
    classes.mkdir(exist_ok=True)
    if label == 'before':
        content = subprocess.check_output(['git','show','ca4cb44:backend/src/main/java/cn/dormlink/dispatch/RoutePlanner.java'],cwd=ROOT)
    else:
        content = (ROOT / 'backend/src/main/java/cn/dormlink/dispatch/RoutePlanner.java').read_bytes()
    source.write_bytes(content)
    subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(classes),str(source),str(ROOT/'scripts/PlannerBenchmark.java')],check=True)
    report['cases'][label] = []
    for workers,jobs in ((5,20),(20,100),(50,500)):
        try:
            output = subprocess.check_output(['java','-Xms128m','-Xmx512m','-cp',str(classes),
                'PlannerBenchmark',str(workers),str(jobs)],timeout=45,text=True)
            case = json.loads(output)
        except subprocess.TimeoutExpired:
            case = {'workers':workers,'jobs':jobs,'timeoutSeconds':45}
        report['cases'][label].append(case)
        print(json.dumps({label:case}),flush=True)
destination = ROOT / 'output/acceptance/refactor-planner-performance.json'
destination.parent.mkdir(parents=True,exist_ok=True)
destination.write_text(json.dumps(report,indent=2),encoding='utf-8')
