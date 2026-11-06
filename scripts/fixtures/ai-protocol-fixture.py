"""Local protocol fixture for UI acceptance only; never a real model response."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
        if self.path!='/chat/completions' or body.get('model')!='acceptance-fixture':
            self.send_error(400);return
        manager='宿管' in body.get('messages',[{}])[0].get('content','')
        suggestion=({'summary':'本地协议验收：请核查故障现象与预约安排。','findings':['本地固定响应：这里只验证建议展示和工单引用。'],'suggestedActions':['核对现场现象后，在工单中记录人工处理结果。'],'questions':['是否已与学生确认可上门时间？'],'uncertainty':'固定测试响应，不代表真实AI判断。'} if manager else {'title':'洗手池管道疑似漏水','category':'UTILITIES','summary':'洗手池下方持续滴水，请补充漏点位置与持续时间。','uncertainty':'这是本地协议验收夹具，无法确认真实故障原因。','questions':['关闭水龙头后是否仍滴水？'],'riskTags':[]})
        payload={'id':'local-ui-fixture','choices':[{'message':{'role':'assistant','content':json.dumps(suggestion,ensure_ascii=False)}}],'usage':{'prompt_tokens':24,'completion_tokens':48}}
        raw=json.dumps(payload,ensure_ascii=False).encode('utf-8')
        self.send_response(200);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
        print('LOCAL_FIXTURE_RESPONSE '+('manager' if manager else 'student'),flush=True)
    def log_message(self,*args):pass

HTTPServer(('127.0.0.1',8901),Handler).serve_forever()
