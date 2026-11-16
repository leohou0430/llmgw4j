#!/usr/bin/env python3
"""Official Python SDK clients against both engines and a controlled local upstream.
Install the pinned test dependencies from bin/requirements-sdk.txt.
"""
import argparse, asyncio, base64, hashlib, json, os, socket, subprocess, tempfile, threading, time, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import openai
import anthropic


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    artifact=parser.add_mutually_exclusive_group(required=True)
    artifact.add_argument('--jar',type=Path)
    artifact.add_argument('--binary',type=Path)
    parser.add_argument('--engine',choices=['webflux','vt'])
    parser.add_argument('--out',type=Path,required=True)
    options=parser.parse_args()
    if options.binary and not options.engine:parser.error('--binary requires the engine selected at AOT build time')
    executable=(options.jar or options.binary).resolve();out=options.out.resolve();out.mkdir(parents=True,exist_ok=True)
    (out/'harness.py').write_bytes(Path(__file__).read_bytes())
    report={('binary_sha256' if options.binary else 'jar_sha256'):hashlib.sha256(executable.read_bytes()).hexdigest(),'harness_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'versions':{'openai':openai.__version__,'anthropic':anthropic.__version__},'checks':[]}
    observed=[];mode='normal';attempts=0
    def save(): (out/'sdk-results.json').write_text(json.dumps(report,indent=2)+'\n')
    def chat(tools=False):
        message={'role':'assistant','content':'ok'}
        if tools:message={'role':'assistant','content':None,'tool_calls':[{'id':'call_1','type':'function','function':{'name':'weather','arguments':'{"city":"Paris"}'}}]}
        return {'id':'chat_1','object':'chat.completion','created':1,'model':'upstream-model','choices':[{'index':0,'message':message,'finish_reason':'tool_calls' if tools else 'stop'}],'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}}
    def message(tools=False):
        content=[{'type':'tool_use','id':'tool_1','name':'weather','input':{'city':'Paris'}}] if tools else [{'type':'text','text':'ok'}]
        return {'id':'msg_1','type':'message','role':'assistant','model':'upstream-model','content':content,'stop_reason':'tool_use' if tools else 'end_turn','stop_sequence':None,'usage':{'input_tokens':1,'output_tokens':1}}
    def response():
        return {'id':'resp_1','object':'response','created_at':1,'status':'completed','model':'upstream-model','output':[{'type':'message','id':'msg_1','role':'assistant','status':'completed','content':[{'type':'output_text','text':'ok','annotations':[]}]}],'usage':{'input_tokens':1,'output_tokens':1,'total_tokens':2}}
    class Mock(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def respond(self,body,status=200,media='application/json'):
            data=body if isinstance(body,bytes) else json.dumps(body).encode()
            self.send_response(status);self.send_header('Content-Type',media);self.send_header('Content-Length',str(len(data)));self.send_header('X-Request-Id','upstream-id');self.end_headers()
            try:
                # Split bytes independently of SSE records; SDKs must see complete events.
                for offset in range(0,len(data),17):self.wfile.write(data[offset:offset+17]);self.wfile.flush()
            except (BrokenPipeError,ConnectionResetError):pass
        def do_POST(self):
            nonlocal attempts
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                pieces=[]
                while True:
                    size=int(self.rfile.readline().split(b';')[0],16)
                    if not size:
                        while self.rfile.readline() not in (b'\r\n',b'\n',b''):pass
                        break
                    pieces.append(self.rfile.read(size));assert self.rfile.read(2)==b'\r\n'
                raw=b''.join(pieces)
            else:raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
            body={} if self.path in ('/v1/files','/v1/audio/transcriptions') else json.loads(raw)
            observed.append({'path':self.path,'body':body,'raw':raw,'headers':{k.lower():v for k,v in self.headers.items()},
                             'header_values':{name:self.headers.get_all(name,[]) for name in ('authorization','x-api-key','anthropic-version')}})
            attempts+=1
            if mode=='error' or (mode=='retry' and attempts==1):
                self.respond({'error':{'message':'controlled unavailable','type':'upstream_error'}},503);return
            if self.path=='/v1/files':self.respond({'id':'file_1','object':'file','bytes':2,'created_at':1,'filename':'input.jsonl','purpose':'batch'});return
            if self.path=='/v1/audio/transcriptions':self.respond({'text':'transcribed'});return
            if self.path=='/v1/audio/speech':self.respond(b'\x00\xffaudio-bytes',media='audio/mpeg');return
            if self.path=='/v1/embeddings':self.respond({'object':'list','data':[{'object':'embedding','index':0,'embedding':[1.0,0.0]}],'model':'upstream-model','usage':{'prompt_tokens':1,'total_tokens':1}});return
            if self.path=='/v1/batches':
                if 'model' in body:self.respond({'error':{'message':'batch create must not acquire a model field'}},400);return
                self.respond({'id':'batch_1','object':'batch','endpoint':body['endpoint'],'input_file_id':body['input_file_id'],'completion_window':'24h','status':'validating','created_at':1});return
            if not body.get('stream'):
                self.respond(message(bool(body.get('tools'))) if self.path.endswith('/messages') else response() if self.path.endswith('/responses') else chat(bool(body.get('tools'))));return
            if self.path.endswith('/messages'):
                m=message();m.update(content=[],stop_reason=None,usage={'input_tokens':1,'output_tokens':0})
                events=[('message_start',{'type':'message_start','message':m}),('content_block_start',{'type':'content_block_start','index':0,'content_block':{'type':'text','text':''}}),('content_block_delta',{'type':'content_block_delta','index':0,'delta':{'type':'text_delta','text':'ok'}}),('content_block_stop',{'type':'content_block_stop','index':0}),('message_delta',{'type':'message_delta','delta':{'stop_reason':'end_turn','stop_sequence':None},'usage':{'output_tokens':1}}),('message_stop',{'type':'message_stop'})]
                if body.get('tools'):
                    events[1][1]['content_block']={'type':'tool_use','id':'tool_1','name':'weather','input':{}}
                    events[2][1]['delta']={'type':'input_json_delta','partial_json':'{"city":"Paris"}'}
                    events[4][1]['delta']['stop_reason']='tool_use'
                if mode=='truncated':events=events[:-1]
                data=''.join('event: '+event+'\ndata: '+json.dumps(value)+'\n\n' for event,value in events)
            elif self.path.endswith('/responses'):
                events=[{'type':'response.output_text.delta','sequence_number':0,'item_id':'msg_1','output_index':0,'content_index':0,'delta':'ok','logprobs':[]},{'type':'response.completed','sequence_number':1,'response':response()}]
                if mode=='truncated':events=events[:1]
                data=''.join('event: '+value['type']+'\ndata: '+json.dumps(value)+'\n\n' for value in events)
            else:
                chunks=[{'id':'chat_1','object':'chat.completion.chunk','created':1,'model':'upstream-model','choices':[{'index':0,'delta':{'role':'assistant','content':'ok'},'finish_reason':None}]},{'id':'chat_1','object':'chat.completion.chunk','created':1,'model':'upstream-model','choices':[{'index':0,'delta':{},'finish_reason':'stop'}],'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}}]
                if body.get('tools'):
                    chunks[0]['choices'][0]['delta']={'role':'assistant','tool_calls':[{'index':0,'id':'call_1','type':'function','function':{'name':'weather','arguments':'{"city":'}}]}
                    chunks.insert(1,{'id':'chat_1','object':'chat.completion.chunk','created':1,'model':'upstream-model','choices':[{'index':0,'delta':{'tool_calls':[{'index':0,'function':{'arguments':'"Paris"}'}}]},'finish_reason':None}]})
                    chunks[-1]['choices'][0]['finish_reason']='tool_calls'
                data=''.join('data: '+json.dumps(value)+'\n\n' for value in (chunks[:1] if mode=='truncated' else chunks))
                if mode!='truncated':data+='data: [DONE]\n\n'
            self.respond(data.encode(),media='text/event-stream')
        def do_GET(self):self.respond(b'{}',media='application/octet-stream')
    server=ThreadingHTTPServer(('127.0.0.1',0),Mock);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    process=None
    def check(engine,name,action):
        row={'engine':engine,'name':name};report['checks'].append(row)
        try:action();row['passed']=True;print('PASS',engine,name,flush=True)
        except Exception as error:row.update(passed=False,error=repr(error));print('FAIL',engine,name,repr(error),flush=True)
        save()
    def stop():
        if process is not None and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait()
    def equal(actual,expected):assert actual==expected,(actual,expected)
    def status_error(action,status):
        try:action()
        except (openai.APIStatusError,anthropic.APIStatusError) as error:equal(error.status_code,status)
        else:raise AssertionError('expected SDK HTTP error')
    try:
        for engine in ([options.engine] if options.engine else ['webflux','vt']):
            port=free_port();mode='normal';attempts=0
            observed.clear()
            with tempfile.TemporaryDirectory(prefix='llmgw-sdk-') as temp,(out/(engine+'.log')).open('w') as log:
                cmd=([str(executable)] if options.binary else ['java','-jar',str(executable)])+['--spring.profiles.active='+engine,f'--server.port={port}','--server.address=127.0.0.1','--gateway.mock.enabled=false','--gateway.keys-store-path=','--gateway.governance.snapshot-path=','--gateway.api-keys[0].id=sdk','--gateway.api-keys[0].token=sdk-client','--gateway.api-keys[0].quota-tokens=1000000','--gateway.governance.rate-limit-tokens-per-second=1000000','--gateway.governance.rate-limit-burst=1000000']
                cmd+=['--gateway.api-keys[1].id=foreign','--gateway.api-keys[1].token=sdk-foreign','--gateway.api-keys[1].quota-tokens=1000000']
                for i,protocol,provider in [(0,'*','openai'),(1,'messages','anthropic')]:
                    for name,value in {'id':'sdk-'+provider,'protocol':protocol,'model':'*','target-model':'upstream-model','target-url':f'http://127.0.0.1:{server.server_port}/v1','api-key':'sdk-upstream','provider':provider}.items():cmd.append(f'--gateway.routes[{i}].{name}={value}')
                report.setdefault('commands',{})[engine]=cmd;save();process=subprocess.Popen(cmd,cwd=temp,env={**os.environ,"LLMGW_ROOT_KEY":base64.b64encode(os.urandom(32)).decode()},stdout=log,stderr=subprocess.STDOUT)
                try:
                    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
                    for _ in range(160):
                        if process.poll() is not None:raise RuntimeError('gateway startup failed')
                        try:
                            with opener.open(f'http://127.0.0.1:{port}/actuator/health',timeout=1) as result:
                                if result.status==200:break
                        except OSError:pass
                        time.sleep(.25)
                    else:raise RuntimeError('gateway readiness timeout')
                    with openai.OpenAI(api_key='sdk-client',base_url=f'http://127.0.0.1:{port}/v1',max_retries=0,timeout=5) as oa,anthropic.Anthropic(api_key='sdk-client',base_url=f'http://127.0.0.1:{port}',max_retries=0,timeout=5) as ant:
                        args={'model':'audit-model','messages':[{'role':'user','content':'sdk-json'}]}
                        check(engine,'openai-chat-json',lambda:equal(oa.chat.completions.create(**args).choices[0].message.content,'ok'))
                        def tools():
                            value=oa.chat.completions.create(**args,tools=[{'type':'function','function':{'name':'weather','parameters':{'type':'object'}}}],extra_body={'vendor_extension':{'enabled':True}})
                            equal(value.choices[0].message.tool_calls[0].function.name,'weather');equal(json.loads(value.choices[0].message.tool_calls[0].function.arguments),{'city':'Paris'});equal(observed[-1]['body']['vendor_extension'],{'enabled':True})
                        check(engine,'openai-tools-and-extension',tools)
                        def stream_chat():
                            with oa.chat.completions.create(**args,stream=True,stream_options={'include_usage':True}) as events:items=list(events)
                            equal(''.join(c.choices[0].delta.content or '' for c in items if c.choices),'ok');equal(items[-1].usage.total_tokens,2)
                        check(engine,'openai-chat-sse-usage',stream_chat)
                        def stream_tools():
                            with oa.chat.completions.create(**args,stream=True,tools=[{'type':'function','function':{'name':'weather','parameters':{'type':'object'}}}]) as events:items=list(events)
                            fragments=[call.function.arguments or '' for item in items for choice in item.choices for call in choice.delta.tool_calls or []]
                            equal(json.loads(''.join(fragments)),{'city':'Paris'});equal(items[-1].choices[0].finish_reason,'tool_calls')
                        check(engine,'openai-streaming-tool-arguments',stream_tools)
                        check(engine,'openai-responses-json',lambda:equal(oa.responses.create(model='audit-model',input='sdk-responses').output_text,'ok'))
                        def stream_responses():
                            with oa.responses.create(model='audit-model',input='sdk-responses',stream=True) as events:items=list(events)
                            equal(''.join(e.delta for e in items if e.type=='response.output_text.delta'),'ok');equal(items[-1].response.usage.total_tokens,2)
                        check(engine,'openai-responses-sse',stream_responses)
                        check(engine,'anthropic-messages-json',lambda:equal(ant.messages.create(model='audit-model',max_tokens=16,messages=[{'role':'user','content':'sdk-anthropic'}]).content[0].text,'ok'))
                        def stream_messages():
                            with ant.messages.stream(model='audit-model',max_tokens=16,messages=[{'role':'user','content':'sdk-anthropic-stream'}]) as events:
                                equal(''.join(events.text_stream),'ok');equal(events.get_final_message().usage.output_tokens,1)
                        check(engine,'anthropic-messages-sse',stream_messages)
                        tool_args={'model':'audit-model','max_tokens':16,'messages':[{'role':'user','content':'sdk-tools'}],'tools':[{'name':'weather','input_schema':{'type':'object'}}]}
                        check(engine,'anthropic-tool-json',lambda:equal(ant.messages.create(**tool_args).content[0].input,{'city':'Paris'}))
                        def anthropic_tools():
                            with ant.messages.stream(**tool_args) as events:value=events.get_final_message()
                            equal(value.content[0].input,{'city':'Paris'});equal(value.stop_reason,'tool_use')
                        check(engine,'anthropic-streaming-tool-input',anthropic_tools)
                        def auth_headers():
                            rows=[r for r in observed if 'model' in r['body']]
                            equal({r['path'] for r in rows},{'/v1/chat/completions','/v1/responses','/v1/messages'})
                            for r in rows:
                                equal(r['body']['model'],'upstream-model')
                                if r['path'].endswith('/messages'):
                                    equal(r['header_values']['x-api-key'],['sdk-upstream']);equal(r['header_values']['anthropic-version'],[ant.default_headers['anthropic-version']]);assert 'authorization' not in r['headers']
                                else:equal(r['header_values']['authorization'],['Bearer sdk-upstream'])
                        check(engine,'model-rewrite-and-upstream-credentials',auth_headers)
                        mode='error'
                        check(engine,'upstream-503-is-sdk-502-error',lambda:status_error(lambda:oa.chat.completions.create(model='audit-model',messages=[{'role':'user','content':'error-case'}]),502))
                        check(engine,'anthropic-upstream-error-is-sdk-502',lambda:status_error(lambda:ant.messages.create(model='audit-model',max_tokens=16,messages=[{'role':'user','content':'anthropic-error-case'}]),502))
                        mode='retry';attempts=0
                        check(engine,'sdk-one-retry-recovers',lambda:equal(oa.with_options(max_retries=1).chat.completions.create(model='audit-model',messages=[{'role':'user','content':'retry-case'}]).choices[0].message.content,'ok'))
                        equal(attempts,2);mode='normal'
                        def file_roundtrip():
                            value=oa.files.create(file=('input.jsonl',b'{}','application/jsonl'),purpose='batch');equal(value.id,'file_1');equal(oa.files.content(value.id).read(),b'{}')
                            upload=next(r for r in observed if r['path']=='/v1/files')
                            report.setdefault('upload_observations',{})[engine]={'content_type':upload['headers'].get('content-type'),'bytes':len(upload['raw'])}
                            assert upload['headers'].get('content-type','').startswith('multipart/form-data;'),upload['headers']
                            assert b'filename="input.jsonl"' in upload['raw'] and b'{}' in upload['raw']
                            from email.message import Message
                            media=Message();media['Content-Type']=upload['headers']['content-type'];boundary=media.get_param('boundary').encode()
                            assert upload['raw'].startswith(b'--'+boundary+b'\r\n') and upload['raw'].endswith(b'--'+boundary+b'--\r\n')
                        check(engine,'openai-file-upload-content',file_roundtrip)
                        check(engine,'openai-batch-create',lambda:equal(oa.batches.create(input_file_id='file_1',endpoint='/v1/chat/completions',completion_window='24h').id,'batch_1'))
                        def transcription():
                            equal(oa.audio.transcriptions.create(model='audit-model',file=('audio.wav',b'wave-bytes','audio/wav')).text,'transcribed')
                            upload=next(r for r in observed if r['path']=='/v1/audio/transcriptions')
                            assert upload['headers'].get('content-type','').startswith('multipart/form-data;')
                            assert b'wave-bytes' in upload['raw'] and b'audit-model' in upload['raw']
                            assert b'upstream-model' not in upload['raw'],'multipart model fields are byte-preserved'
                        check(engine,'openai-transcription-upload',transcription)
                        check(engine,'openai-speech-binary',lambda:equal(oa.audio.speech.create(model='audit-model',input='say ok',voice='alloy').read(),b'\x00\xffaudio-bytes'))
                        check(engine,'openai-embeddings',lambda:equal(oa.embeddings.create(model='audit-model',input='embed this',encoding_format='float').data[0].embedding,[1.0,0.0]))
                        check(engine,'file-list-project-catalog',lambda:equal([item.id for item in oa.files.list()],['file_1']))
                        def foreign_file():
                            before=len(observed)
                            with openai.OpenAI(api_key='sdk-foreign',base_url=f'http://127.0.0.1:{port}/v1',max_retries=0,timeout=5) as foreign:
                                status_error(lambda:foreign.files.content('file_1'),404)
                                equal(list(foreign.files.list()),[])
                            equal(len(observed),before)
                        check(engine,'foreign-file-sdk-404-without-upstream',foreign_file)
                        mode='truncated'
                        def detects_truncation(create):
                            try:
                                with create() as events:list(events)
                            except (openai.APIError,anthropic.APIError) as error:assert 'without' in str(error),error
                            else:raise AssertionError('SDK silently accepted a truncated stream')
                        check(engine,'chat-sdk-detects-truncation',lambda:detects_truncation(lambda:oa.chat.completions.create(**args,stream=True)))
                        check(engine,'responses-sdk-detects-truncation',lambda:detects_truncation(lambda:oa.responses.create(model='audit-model',input='truncated',stream=True)))
                        check(engine,'messages-sdk-detects-truncation',lambda:detects_truncation(lambda:ant.messages.stream(model='audit-model',max_tokens=16,messages=[{'role':'user','content':'truncated'}])))
                        mode='normal'
                    async def async_clients():
                        async with openai.AsyncOpenAI(api_key='sdk-client',base_url=f'http://127.0.0.1:{port}/v1',max_retries=0,timeout=5) as client:
                            equal((await client.chat.completions.create(**args)).choices[0].message.content,'ok')
                            events=await client.chat.completions.create(**args,stream=True)
                            async with events:items=[item async for item in events]
                            equal(''.join(item.choices[0].delta.content or '' for item in items),'ok')
                        async with anthropic.AsyncAnthropic(api_key='sdk-client',base_url=f'http://127.0.0.1:{port}',max_retries=0,timeout=5) as client:
                            value=await client.messages.create(model='audit-model',max_tokens=16,messages=[{'role':'user','content':'async-messages'}]);equal(value.content[0].text,'ok')
                            async with client.messages.stream(model='audit-model',max_tokens=16,messages=[{'role':'user','content':'async-stream'}]) as events:text=''.join([part async for part in events.text_stream])
                            equal(text,'ok')
                    check(engine,'async-sdk-json-and-sse-both-clients',lambda:asyncio.run(async_clients()))
                    with openai.OpenAI(api_key='invalid',base_url=f'http://127.0.0.1:{port}/v1',max_retries=0,timeout=5) as bad:
                        check(engine,'invalid-key-sdk-401',lambda:status_error(lambda:bad.chat.completions.create(model='audit-model',messages=[{'role':'user','content':'bad-key'}]),401))
                finally:stop()
        report['complete']=all(r.get('passed') for r in report['checks'])
    except BaseException as error:report['failure']=repr(error);raise
    finally:
        stop();server.shutdown();server.server_close();thread.join(timeout=5);save()
    if not report['complete']:raise SystemExit(1)

if __name__=='__main__':main()
