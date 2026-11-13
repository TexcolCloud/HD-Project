"""SDK-backed UI acceptance helper. Observes a fresh device layout before each input."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
sys.stdout.reconfigure(encoding="utf-8")

ROOT=Path(__file__).resolve().parents[1]
HDC=Path('C:/Program Files/Huawei/DevEco Studio/sdk/default/openharmony/toolchains/hdc.exe')
BUNDLE='com.dormlink.residence'
OUT=ROOT/'output/acceptance'
SCREEN=[]

def hdc(*args):
    result=subprocess.run([str(HDC),'-t','127.0.0.1:5555',*args],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
    if result.returncode:raise RuntimeError(result.stderr or result.stdout)
    return result.stdout

def layout():
    global SCREEN
    OUT.mkdir(parents=True,exist_ok=True)
    target='/data/local/tmp/dormlink-ui.json'
    hdc('shell','uitest','dumpLayout','-p',target)
    hdc('file','recv',target,str(OUT/'harmony-current-layout.json'))
    data=json.loads((OUT/'harmony-current-layout.json').read_text(encoding='utf-8'))
    SCREEN=list(map(int,re.findall(r'-?\d+',data.get('attributes',{}).get('bounds',''))))
    nodes=[]
    def visit(node):
        a=node.get('attributes',{})
        if a.get('text') or a.get('id'):nodes.append(a)
        for child in node.get('children',[]):visit(child)
    visit(data)
    return nodes

def main():
    p=argparse.ArgumentParser()
    p.add_argument('action',choices=['inspect','tap','fill','back','capture','swipe','assert'])
    p.add_argument('--id');p.add_argument('--text');p.add_argument('--value');p.add_argument('--type')
    p.add_argument('--label',help='For assert: inspect the visible value to the right of this label')
    p.add_argument('--name',default='harmony-current')
    p.add_argument('--direction',choices=['up','down'],default='up')
    a=p.parse_args()
    if a.action=='back':print(hdc('shell','uitest','uiInput','keyEvent','Back'));return
    if a.action=='swipe':
        # Coordinates come from the observed root display bounds; inspect before using.
        layout()
        if len(SCREEN)!=4:raise RuntimeError('Display bounds missing from observed layout')
        left,top,right,bottom=SCREEN
        x=str((left+right)//2); high=str(top+(bottom-top)*3//10); low=str(top+(bottom-top)*3//4)
        print(hdc('shell','uitest','uiInput','swipe',x,low if a.direction=='up' else high,x,high if a.direction=='up' else low,'1500'));return
    if a.action=='capture':
        OUT.mkdir(parents=True,exist_ok=True)
        remote='/data/local/tmp/'+a.name+'.png'
        hdc('shell','uitest','screenCap','-p',remote)
        print(hdc('file','recv',remote,str(OUT/(a.name+'.png'))));return
    nodes=layout()
    if a.action=='assert':
        if a.value is None:raise ValueError('--value required for assert')
        if a.id and 'password' in a.id.lower():raise ValueError('Password values must not be asserted or logged')
        def visible(n):
            b=list(map(int,re.findall(r'-?\d+',n.get('bounds',''))))
            return len(b)==4 and b[2]>b[0] and b[3]>b[1] and b[3]>SCREEN[1] and b[1]<SCREEN[3] and n.get('visible')!='false'
        nodes=[n for n in nodes if visible(n)]
        if a.label:
            labels=[n for n in nodes if n.get('type')=='Text' and n.get('text')==a.label]
            if len(labels)!=1:raise AssertionError('Expected one observed label; found '+str(len(labels)))
            b=list(map(int,re.findall(r'-?\d+',labels[0]['bounds'])))
            matches=[]
            for n in nodes:
                v=list(map(int,re.findall(r'-?\d+',n.get('bounds',''))))
                if n.get('type')=='Text' and len(v)==4 and v[0]>=b[2] and abs((v[1]+v[3])-(b[1]+b[3]))<20:
                    matches.append(n)
        else:
            matches=[n for n in nodes if (a.id is not None and n.get('id')==a.id) or (a.text is not None and n.get('text')==a.text)]
            if a.type:matches=[n for n in matches if n.get('type')==a.type]
        if len(matches)!=1:raise AssertionError('Expected one observed value; found '+str(len(matches)))
        actual=matches[0].get('text','')
        if actual!=a.value:raise AssertionError('Native UI value mismatch: expected '+repr(a.value)+', observed '+repr(actual))
        print('PASS native UI value:',a.id or a.label or a.text,repr(actual))
        return
    if a.action=='inspect':
        print(json.dumps([{k:n.get(k) for k in ['type','text','id','bounds','enabled','clickable']} for n in nodes],ensure_ascii=False,indent=2));return
    matches=[n for n in nodes if (a.id is not None and n.get('id')==a.id) or (a.text is not None and n.get('text')==a.text)]
    if a.type:matches=[n for n in matches if n.get('type')==a.type]
    usable=[]
    for n in matches:
        b=list(map(int,re.findall(r'-?\d+',n.get('bounds',''))))
        if len(b)==4 and b[2]>b[0] and b[3]>b[1]:usable.append((n,b))
    if len(usable)!=1:raise RuntimeError('Expected one visible target; found '+str(len(usable)))
    node,b=usable[0];x,y=str((b[0]+b[2])//2),str((b[1]+b[3])//2)
    if a.action=='tap':print(hdc('shell','uitest','uiInput','click',x,y))
    else:
        if a.value is None:raise ValueError('--value required')
        hdc('shell','uitest','uiInput','click',x,y)
        # Official SDK KeyCode: CTRL_LEFT=2072, A=2017, DEL=2055.
        # uiInput inputText appends; select all and replace for repeatable form tests.
        hdc('shell','uitest','uiInput','keyEvent','2072','2017')
        hdc('shell','uitest','uiInput','keyEvent','2055')
        print(hdc('shell','uitest','uiInput','text',a.value))
    # Persist the next state for review; the next action still observes again.
    updated=layout()
    print(json.dumps([{'text':n.get('text'),'id':n.get('id'),'bounds':n.get('bounds')} for n in updated if n.get('text') or n.get('id')],ensure_ascii=False))

if __name__=='__main__':main()
