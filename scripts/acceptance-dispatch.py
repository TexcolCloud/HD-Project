"""Exercise dynamic dispatch through real HTTP; creates and closes clearly marked fictional orders."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import runpy
import time

helpers = runpy.run_path(str(Path(__file__).with_name('acceptance-api.py')))
Api, require = helpers['Api'], helpers['require']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='http://127.0.0.1:8080/api/v1')
    args = parser.parse_args()
    report = {'base':args.base,'startedAt':datetime.now(timezone.utc).isoformat(),'checks':[],'orderIds':[]}
    output = Path(__file__).resolve().parents[1] / 'output/acceptance/dispatch-api-results.json'
    managers, students, initial = [], [], []
    created=[]

    def passed(name):
        report['checks'].append({'name':name,'passed':True})
        print('PASS',name,flush=True)

    def wait_board(manager, predicate):
        deadline=time.monotonic()+20
        last=None
        while time.monotonic()<deadline:
            last=manager.ok('GET','/dispatch/board')
            if not last['dirty'] and predicate(last): return last
            time.sleep(.25)
        raise AssertionError('automatic plan did not converge: '+json.dumps(last,ensure_ascii=False))

    def tasks(board):
        return [(r['manager']['userId'],t) for r in board['routes'] for t in r['tasks']]

    def find(board,order_id):
        return next(t for _,t in tasks(board) if t['orderId']==order_id)

    def settings(manager,accepting,building=None,floor=1):
        current=manager.ok('GET','/dispatch/board')['me']
        body={'acceptingNewTasks':accepting,'expectedVersion':current['version'],'availableUntil':(datetime.now(timezone.utc)+timedelta(hours=4)).isoformat()}
        if building is not None: body.update(positionBuildingId=building,positionFloorNo=floor)
        return manager.ok('PATCH','/dispatch/me',body)

    suffix=datetime.now().strftime('%Y%m%d%H%M%S')
    def create(student,label,urgent=False):
        body={'title':'调度验收-'+suffix+'-'+label,'description':'本次为虚构调度验收，检查洗手池下方滴水现象。','category':'UTILITIES','priority':'URGENT' if urgent else 'NORMAL','urgentReason':'虚构紧急插单验收' if urgent else '', 'contactName':'演示学生','contactPhone':'13800000000','contactTimeSlot':'','reportImageIds':[]}
        order=student.ok('POST','/repairs',body)
        created.append((student,order['id']))
        report['orderIds'].append(order['id'])
        return order

    try:
        a,b=Api(args.base,'manager1'),Api(args.base,'manager2')
        duplicate=Api(args.base,'manager1')
        sa,sb=Api(args.base,'student1'),Api(args.base,'student2')
        managers=[a,b,duplicate];students=[sa,sb]
        for manager in managers: manager.ok('POST','/dispatch/heartbeat',{})
        board=wait_board(a,lambda x:x['onlineCount']==2)
        require(sa.request('POST','/dispatch/heartbeat',{})[0]==403,'student entered manager pool')
        duplicate.ok('POST','/auth/logout',{})
        require(a.ok('GET','/dispatch/board')['onlineCount']==2,'one account counted twice or logout removed other session')
        passed('live account deduplication, student rejection and independent logout')
        initial=[(m,m.ok('GET','/dispatch/board')['me']) for m in (a,b)]
        room_a,room_b=sa.ok('GET','/me/room'),sb.ok('GET','/me/room')
        settings(a,False,room_a['buildingId'],room_a['floorNo'])
        settings(b,False,room_b['buildingId'],room_b['floorNo'])
        first=create(sa,'A-current')
        board=wait_board(a,lambda x:any(i['task']['orderId']==first['id'] for i in x['unassigned']))
        require(board['participatingCount']==0,'paused staff still participating')
        passed('no available participant keeps new job visible with an explanation')
        settings(a,True);settings(b,True)
        second=create(sb,'B-other')
        board=wait_board(a,lambda x:all(any(t['orderId']==o['id'] for _,t in tasks(x)) for o in (first,second)))
        owner_a=next(owner for owner,t in tasks(board) if t['orderId']==first['id'])
        owner_b=next(owner for owner,t in tasks(board) if t['orderId']==second['id'])
        require(owner_a==a.user['id'] and owner_b==b.user['id'],'scope or expected multi-manager allocation')
        b_board=b.ok('GET','/dispatch/board')
        require(all(t['buildingId']==room_b['buildingId'] for _,t in tasks(b_board)),'A task leaked into B scope')
        passed('two manager routes and hard building authorization')
        duplicate=Api(args.base,'manager1');managers.append(duplicate)
        duplicate.ok('POST','/dispatch/heartbeat',{})
        def claim(manager):
            return manager.request('POST','/dispatch/tasks/'+first['id']+'/claim',{'expectedOrderVersion':first['version']})[0]
        with ThreadPoolExecutor(max_workers=2) as pool: codes=list(pool.map(claim,[a,duplicate]))
        require(sorted(codes)==[200,409],f'duplicate claim race {codes}')
        board=wait_board(a,lambda x:find(x,first['id'])['state']=='ASSIGNED')
        current=find(board,first['id'])
        a.ok('POST','/dispatch/tasks/'+first['id']+'/depart',{'expectedTaskVersion':current['version']})
        board=wait_board(a,lambda x:find(x,first['id'])['state']=='EN_ROUTE')
        added=create(sa,'A-dynamic')
        board=wait_board(a,lambda x:any(t['orderId']==added['id'] for _,t in tasks(x)))
        mine=next(r for r in board['routes'] if r['manager']['userId']==a.user['id'])
        require(mine['tasks'][0]['orderId']==first['id'] and mine['tasks'][0]['locked'],'in-flight next stop was changed')
        current=find(board,first['id'])
        arrived=a.ok('POST','/dispatch/tasks/'+first['id']+'/arrive',{'expectedTaskVersion':current['version']})
        require(arrived['repairStatus']=='ACCEPTED','arrive silently started the repair')
        order=a.ok('GET','/repairs/'+first['id'])
        a.ok('POST','/repairs/'+first['id']+'/start',{'expectedVersion':order['version']})
        urgent=create(sa,'A-urgent',True)
        board=wait_board(a,lambda x:any(t['orderId']==urgent['id'] for _,t in tasks(x)))
        mine=next(r for r in board['routes'] if r['manager']['userId']==a.user['id'])
        require(mine['tasks'][0]['orderId']==first['id'],'onsite task was moved')
        require(next(i for i,t in enumerate(mine['tasks']) if t['orderId']==urgent['id'])<next(i for i,t in enumerate(mine['tasks']) if t['orderId']==added['id']),'urgent arrival not prioritized in the feasible suffix')
        passed('unique concurrent claim, dynamic insertion and in-flight/onsite freeze')
        other=find(board,added['id'])
        a.ok('POST','/dispatch/tasks/'+added['id']+'/claim',{'expectedOrderVersion':other['orderVersion']})
        board=wait_board(a,lambda x:find(x,added['id'])['state']=='ASSIGNED')
        require(a.request('POST','/dispatch/tasks/'+added['id']+'/depart',{'expectedTaskVersion':find(board,added['id'])['version']})[0]==409,'one manager executed two tasks simultaneously')
        current=find(board,first['id'])
        a.ok('POST','/dispatch/tasks/'+first['id']+'/block',{'expectedTaskVersion':current['version'],'reason':'虚构验收：等待配件'})
        board=wait_board(a,lambda x:any(i['task']['orderId']==first['id'] and i['reasonCode']=='BLOCKED' for i in x['unassigned']))
        blocked=next(i['task'] for i in board['unassigned'] if i['task']['orderId']==first['id'])
        resumed=a.ok('POST','/dispatch/tasks/'+first['id']+'/resume',{'expectedTaskVersion':blocked['version'],'reason':'虚构验收：配件到位'})
        require(resumed['state']=='ASSIGNED' and resumed['repairStatus']=='PROCESSING','physical resume conflated with repair state')
        a.ok('POST','/dispatch/tasks/'+first['id']+'/depart',{'expectedTaskVersion':resumed['version']})
        passed('single execution guard, blocked task ownership and resuming a processing repair')
        other_order=a.ok('GET','/repairs/'+added['id'])
        start=datetime.now(timezone.utc)+timedelta(minutes=1)
        a.ok('PATCH','/repairs/'+added['id']+'/visit',{'expectedVersion':other_order['version'],'windowStart':start.isoformat(),'windowEnd':(start+timedelta(minutes=2)).isoformat(),'windowConfirmed':True,'estimatedMinutes':30,'visitRequired':True,'waitingReason':''})
        board=wait_board(a,lambda x:any(i['task']['orderId']==added['id'] and i['reasonCode']=='TIME_WINDOW_CONFLICT' for i in x['unassigned']))
        passed('confirmed appointment conflicts are explained instead of silently omitted')
        order_b=b.ok('GET','/repairs/'+second['id'])
        b.ok('POST','/dispatch/tasks/'+second['id']+'/claim',{'expectedOrderVersion':order_b['version']})
        board_b=wait_board(b,lambda x:find(x,second['id'])['state']=='ASSIGNED')
        order_b=b.ok('GET','/repairs/'+second['id'])
        transferred=b.ok('POST','/dispatch/tasks/'+second['id']+'/handoff',{'targetManagerId':a.user['id'],'reason':'虚构验收交接，双方在同一授权楼栋','expectedOrderVersion':order_b['version'],'expectedTaskVersion':find(board_b,second['id'])['version']})
        require(transferred['handlerUserId']==a.user['id'],'explicit handoff did not update responsibility')
        require(any(e['action']=='handoff' for e in a.ok('GET','/repairs/'+second['id']+'/events')),'handoff history missing')
        passed('explicit handoff preserves building scope and progress history')
        report['passed']=True
    except Exception as error:
        report['passed']=False;report['error']=str(error)
        raise
    finally:
        cleanup=[]
        for student,identifier in created:
            try:
                order=student.ok('GET','/repairs/'+identifier)
                if order['status']=='ACCEPTED':
                    manager=next(m for m in managers[:2] if m.user['id']==order['handlerUserId'])
                    order=manager.ok('POST','/repairs/'+identifier+'/start',{'expectedVersion':order['version']})
                if order['status']=='PROCESSING':
                    manager=next(m for m in managers[:2] if m.user['id']==order['handlerUserId'])
                    order=manager.ok('POST','/repairs/'+identifier+'/finish',{'expectedVersion':order['version'],'resultDescription':'虚构调度验收结束，已完成演示检查。','resultImageIds':[]})
                if order['status']=='PENDING_CONFIRMATION': student.ok('POST','/repairs/'+identifier+'/confirm',{'expectedVersion':order['version']})
                elif order['status']=='SUBMITTED': student.ok('POST','/repairs/'+identifier+'/cancel',{'expectedVersion':order['version'],'reason':'虚构调度验收结束，保留历史'})
                cleanup.append({'orderId':identifier,'closed':True})
            except Exception as error: cleanup.append({'orderId':identifier,'closed':False,'error':str(error)})
        for manager,old in initial:
            try:
                current=manager.ok('GET','/dispatch/board')['me']
                manager.ok('PATCH','/dispatch/me',{'expectedVersion':current['version'],'acceptingNewTasks':old['acceptingNewTasks'],'availableUntil':old['availableUntil'],'positionBuildingId':old['positionBuildingId'],'positionFloorNo':old['positionFloorNo']})
            except Exception as error: cleanup.append({'settingsRestored':False,'error':str(error)})
        for api in managers+students:
            try: api.request('POST','/auth/logout',{})
            except Exception: pass
        report['cleanup']=cleanup;report['finishedAt']=datetime.now(timezone.utc).isoformat()
        output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__': main()
