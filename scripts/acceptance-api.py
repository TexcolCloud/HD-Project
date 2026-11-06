"""Run cross-client acceptance against a running local DormLink MySQL backend.
Creates uniquely named fictional records; does not clear existing data.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
import http.cookiejar
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
sys.stdout.reconfigure(encoding="utf-8")

class Api:
    def __init__(self, base, username=None):
        self.base = base.rstrip('/')
        self.headers = {}
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        if username:
            status, payload = self.request('POST', '/auth/login', {'username':username,'password':'Demo@123456','clientType':'MOBILE'})
            require(status == 200, f'login {username}: {status} {payload}')
            self.headers['Authorization'] = 'Bearer ' + payload['data']['token']
            self.user = payload['data']['user']

    def request(self, method, path, body=None, *, key=None, raw=None, content_type=None):
        headers = dict(self.headers)
        if body is not None:
            raw = json.dumps(body, ensure_ascii=False).encode()
            headers['Content-Type'] = 'application/json'
        if content_type:
            headers['Content-Type'] = content_type
        if method not in ('GET','HEAD'):
            headers['Idempotency-Key'] = key or str(uuid.uuid4())
        req = urllib.request.Request(self.base + path, data=raw, headers=headers, method=method)
        try:
            response = self.opener.open(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        content = response.read()
        if 'json' in response.headers.get('Content-Type',''):
            return response.status, json.loads(content)
        return response.status, content

    def ok(self, method, path, body=None, **kwargs):
        status, response = self.request(method, path, body, **kwargs)
        require(200 <= status < 300, f'{method} {path}: {status} {response}')
        return response['data']

def require(condition, message):
    if not condition:
        raise AssertionError(message)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='http://127.0.0.1:8080/api/v1')
    args = parser.parse_args()
    report = {'base':args.base,'startedAt':datetime.now(timezone.utc).isoformat(),'checks':[]}
    def passed(name):
        report['checks'].append({'name':name,'passed':True})
        print('PASS', name)
    output = Path(__file__).resolve().parents[1] / 'output/acceptance/api-results.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        anon = Api(args.base)
        require(anon.ok('GET','/health')['database'] == 'UP', 'database health')
        require(anon.request('GET','/rooms')[0] == 401, 'anonymous access must be rejected')
        passed('SQL health and anonymous authorization')
        manager, manager_b = Api(args.base,'manager1'), Api(args.base,'manager2')
        student, other = Api(args.base,'student1'), Api(args.base,'student2')
        room = student.ok('GET','/me/room')
        require(manager_b.request('GET','/rooms/'+room['id'])[0] == 404, 'building scope leak')
        require(student.request('GET','/rooms')[0] == 403, 'student admin access')
        passed('student role and cross-building resource scope')
        # Real multipart upload, using a fictional one-pixel fixture.
        png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC')
        boundary = 'DormLink' + uuid.uuid4().hex
        raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="usage"\r\n\r\nREPORT\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="acceptance.png"\r\nContent-Type: image/png\r\n\r\n').encode() + png + f'\r\n--{boundary}--\r\n'.encode()
        image = student.ok('POST','/files',raw=raw,content_type='multipart/form-data; boundary='+boundary)
        require(other.request('GET','/files/'+image['id']+'/content')[0] == 404, 'temporary image leak')
        passed('multipart image upload and private attachment access')
        suffix = str(int(time.time()*1000))
        body = {'title':'验收报修'+suffix,'description':'洗手池下方管道持续滴水，请安排检查。','category':'UTILITIES','priority':'NORMAL','contactName':'演示学生','contactPhone':'13800000000','contactTimeSlot':'18点后','reportImageIds':[image['id']]}
        key = str(uuid.uuid4())
        order = student.ok('POST','/repairs',body,key=key)
        duplicate = student.ok('POST','/repairs',body,key=key)
        require(order['id'] == duplicate['id'], 'idempotent duplicate created another order')
        changed = dict(body,title=body['title']+'变更')
        require(student.request('POST','/repairs',changed,key=key)[0] == 409, 'reused key with changed content')
        require(other.request('GET','/repairs/'+order['id'])[0] == 404, 'other student reads order')
        require(manager_b.request('GET','/repairs/'+order['id'])[0] == 404, 'other building manager reads order')
        require(student.request('POST','/repairs/'+order['id']+'/confirm',{'expectedVersion':order['version']})[0] == 409, 'skipped lifecycle stage')
        passed('repair creation, deduplication and object authorization')
        path = '/repairs/'+order['id']
        def accept(_):
            return manager.request('POST',path+'/accept',{'expectedVersion':order['version']})[0]
        with ThreadPoolExecutor(max_workers=2) as pool:
            codes = list(pool.map(accept,range(2)))
        require(sum(200 <= c < 300 for c in codes)==1 and 409 in codes, f'accept race: {codes}')
        order = manager.ok('GET',path)
        order = manager.ok('POST',path+'/start',{'expectedVersion':order['version']})
        order = manager.ok('POST',path+'/finish',{'expectedVersion':order['version'],'resultDescription':'已更换密封圈，试水无滴漏。','resultImageIds':[]})
        order = student.ok('POST',path+'/rework',{'expectedVersion':order['version'],'reason':'观察后仍有少量滴水。'})
        require(order['status']=='PROCESSING', 'rework state')
        order = manager.ok('POST',path+'/finish',{'expectedVersion':order['version'],'resultDescription':'已重新固定接头并检查。','resultImageIds':[]})
        order = student.ok('POST',path+'/confirm',{'expectedVersion':order['version']})
        require(order['status']=='COMPLETED', 'student completion')
        require(len(student.ok('GET',path+'/events'))>=7, 'event history was overwritten')
        passed('concurrent acceptance, repair, rework and student confirmation')
        # Isolated room and students allow safe repeat runs without clearing the DB.
        new_room = manager.ok('POST','/rooms',{'buildingId':room['buildingId'],'roomNo':'T'+suffix[-10:],'floorNo':1,'capacity':1,'enabled':True,'remark':'自动验收数据'})
        new_students=[]
        for i in range(2):
            new_students.append(manager.ok('POST','/students',{'studentNo':suffix+str(i),'name':'验收学生'+str(i),'college':'示例学院','phone':'13800000000'}))
        def check_in(s):
            return manager.request('POST','/residences/check-in',{'studentId':s['id'],'roomId':new_room['id']})
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses=list(pool.map(check_in,new_students))
        require(sum(200<=c<300 for c,_ in responses)==1 and any(c==409 for c,_ in responses), 'room over-capacity race')
        residence=next(p['data'] for c,p in responses if 200<=c<300)
        require(manager.ok('GET','/rooms/'+new_room['id'])['occupiedCount']==1, 'occupancy mismatch')
        manager.ok('POST','/residences/'+residence['id']+'/check-out',{'reason':'自动验收退宿','expectedVersion':residence['version']})
        passed('concurrent room capacity and check-out')
        now=datetime.now(timezone(timedelta(hours=8)))
        score={'roomId':new_room['id'],'inspectionDate':now.date().isoformat(),'round':'ROUTINE','floorScore':23,'deskScore':23,'bedScore':23,'bathroomScore':23,'issueNote':'','remediationNote':'','imageIds':[]}
        inspection=manager.ok('POST','/hygiene-inspections',score)
        require(inspection['totalScore']==92 and inspection['grade']=='EXCELLENT','score total or grade')
        require(manager.request('POST','/hygiene-inspections',score)[0]==409,'duplicate inspection accepted')
        revised=dict(score,floorScore=0,expectedVersion=inspection['version'],correctionReason='验收修正测试')
        inspection=manager.ok('PATCH','/hygiene-inspections/'+inspection['id'],revised)
        require(inspection['totalScore']==69 and inspection['grade']=='PASS','corrected score')
        passed('hygiene totals, duplicate round and traced correction')
        visitor=manager.ok('POST','/visitors',{'visitorName':'验收访客'+suffix[-4:],'phone':'139'+suffix[-8:],'visitedStudentId':student.user['studentId'],'purpose':'自动验收探访'})
        require(visitor['status']=='VISITING','visitor initial state')
        visitor=manager.ok('POST','/visitors/'+visitor['id']+'/leave',{'expectedVersion':visitor['version']})
        require(visitor['status']=='LEFT' and visitor['leftAt'],'visitor leave state')
        passed('visitor arrival and departure')
        overview=manager.ok('GET','/statistics/overview')
        require(overview['occupiedCount']<=overview['capacity'],'invalid statistics')
        for category in ['repairs','hygiene','visitors']:
            manager.ok('GET','/statistics/'+category)
        ai_code,ai=student.request('POST','/ai/repair-suggestions',{'description':'洗手池下方滴水','imageIds':[],'consentToExternalAnalysis':True})
        require(ai_code in (200,429,503),f'AI response contract: {ai_code}')
        if ai_code==200:require(ai['data']['requiresHumanReview'] is True,'AI human review')
        passed('real statistics and configurable AI availability')
        web=Api(args.base)
        csrf=web.ok('GET','/auth/csrf')
        require(web.request('POST','/auth/login',{'username':'manager1','password':'Demo@123456','clientType':'WEB'})[0]==403,'Web login accepts missing CSRF')
        web.headers[csrf['headerName']]=csrf['token']
        web.ok('POST','/auth/login',{'username':'manager1','password':'Demo@123456','clientType':'WEB'})
        csrf=web.ok('GET','/auth/csrf');web.headers[csrf['headerName']]=csrf['token']
        require(web.ok('GET','/auth/me')['role']=='MANAGER','cookie session')
        web.ok('POST','/auth/logout',{})
        require(web.request('GET','/auth/me')[0]==401,'revoked cookie still valid')
        passed('Web Cookie, CSRF and session revocation')
        report['passed']=True
    except Exception as error:
        report['passed']=False;report['error']=str(error)
        print('FAIL',error,file=sys.stderr)
        raise
    finally:
        report['finishedAt']=datetime.now(timezone.utc).isoformat()
        output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':main()
