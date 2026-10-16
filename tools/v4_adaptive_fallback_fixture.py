"""Opt-in local V4 fixture; private aligned media and generated DRM assets stay outside Git.

Run on the emulator host with --hls-root and --state-dir; the normal UI driver
uses port 18784. Credentials and license keys below belong only to this fixture.
"""
import argparse,http.server,json,pathlib,urllib.parse,time,threading
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--hls-root', type=pathlib.Path, required=True)
parser.add_argument('--state-dir', type=pathlib.Path, required=True)
args=parser.parse_args()
ROOT=args.state_dir.resolve()
ROOT.mkdir(parents=True,exist_ok=True)
MEDIA=args.hls_root.resolve()
# This fixture uses the existing aligned ladder; reject another layout before
# serving a master that might silently remove the wrong representation.
master_lines=(MEDIA/'master.m3u8').read_text().splitlines()
if not (len(master_lines)==9 and master_lines[:3]==['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-INDEPENDENT-SEGMENTS']
        and master_lines[4::2]==['high/index.m3u8','hd/index.m3u8','sd/index.m3u8']
        and all(master_lines[i].startswith('#EXT-X-STREAM-INF:') for i in (3,5,7))):
    parser.error('Expected the aligned high/hd/sd fixture master layout')
adaptive_master=('\n'.join(master_lines[:3]+master_lines[5:])+'\n').encode()
lock=threading.Lock(); failed=None; code=503; license_mode='invalid'
MOVIES={701:('V4 Adaptive UI Fixture','master.m3u8'),702:('V4 Direct 4K UI Fixture','high/index.m3u8'),703:('V4 Same 1080 UI Fixture','hd/index.m3u8')}
def log(**data):
    with lock:
        with (ROOT/'server-events.jsonl').open('a') as f:f.write(json.dumps({'time':time.time(),**data})+'\n')
def movie(i):
    return {'stream_id':i,'name':MOVIES[i][0],'category_id':'1','container_extension':'m3u8','rating':'8','tmdb':str(99999700+i),'direct_source':f'http://10.0.2.2:18784/asset/{MOVIES[i][1]}?expires={int(time.time())+7200}'}
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def reply(self,data,kind='application/json',status=200):
        raw=data if isinstance(data,bytes) else json.dumps(data).encode()
        self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(raw)));self.send_header('Cache-Control','no-store');self.end_headers()
        try:self.wfile.write(raw)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
    def do_GET(self):
        global failed,code,license_mode
        p=urllib.parse.urlparse(self.path);q=urllib.parse.parse_qs(p.query)
        if p.path=='/control':
            failed=q.get('fail',[None])[0];code=int(q.get('code',['503'])[0]);license_mode=q.get('license',['invalid'])[0];log(event='control',failed=failed,code=code,licenseMode=license_mode)
            return self.reply({'failed':failed,'code':code})
        if p.path=='/drm-playlist.m3u':return self.reply((ROOT/'drm-playlist.m3u').read_bytes(),'audio/x-mpegurl')
        if p.path.startswith('/movie/'):
            name=p.path.removeprefix('/movie/')
            if name not in ('drm.mpd','protected720.mp4','clear1080.mp4'):return self.reply(b'',status=404)
            path=ROOT/name
            raw=path.read_bytes();size=len(raw);start=0;end=size-1
            value=self.headers.get('Range')
            if value:
                import re
                match=re.fullmatch(r'bytes=(\d+)-(\d*)',value)
                if not match:return self.reply(b'',status=416)
                start=int(match[1]);end=min(int(match[2]) if match[2] else end,end)
                if start>end:return self.reply(b'',status=416)
                raw=raw[start:end+1]
            log(event='drm-media',path=name,range=value,bytes=len(raw))
            if not value:return self.reply(raw,'application/dash+xml' if name.endswith('.mpd') else 'video/mp4')
            self.send_response(206);self.send_header('Content-Type','video/mp4');self.send_header('Content-Length',str(len(raw)))
            self.send_header('Content-Range',f'bytes {start}-{end}/{size}');self.end_headers()
            try:self.wfile.write(raw)
            except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
            return
        if p.path=='/player_api.php':
            action=q.get('action',[''])[0]
            if not action:data={'user_info':{'username':'validation','password':'validation','auth':1,'status':'Active','max_connections':'5'},'server_info':{'url':'10.0.2.2','port':'18784','server_protocol':'http','timezone':'UTC','timestamp_now':int(time.time())}}
            elif action=='get_vod_categories':data=[{'category_id':'1','category_name':'V4 UI Validation','parent_id':0}]
            elif action=='get_vod_streams':data=[movie(i) for i in MOVIES]
            elif action=='get_vod_info':
                i=int(q['vod_id'][0]);data={'info':{'name':MOVIES[i][0],'duration_secs':300,'duration':'00:05:00','tmdb_id':99999700+i},'movie_data':movie(i)}
            else:data=[]
            log(event='api',action=action);return self.reply(data)
        if not p.path.startswith('/asset/'):return self.reply(b'',status=404)
        rel=p.path.removeprefix('/asset/')
        path=(MEDIA/rel).resolve()
        if not path.is_relative_to(MEDIA.resolve()) or not path.is_file():return self.reply(b'',status=404)
        if rel.startswith(str(failed)+'/') and path.suffix=='.ts':
            log(event='failure',path=rel,status=code);return self.reply(b'Controlled rendition failure',status=code)
        raw=path.read_bytes()
        if rel=='master.m3u8':
            raw=adaptive_master
        status=200;size=len(raw);start=0;end=size-1
        value=self.headers.get('Range')
        if value:
            import re
            m=re.fullmatch(r'bytes=(\d+)-(\d*)',value)
            if not m:return self.reply(b'',status=416)
            start=int(m[1]);end=min(int(m[2]) if m[2] else end,end)
            if start>end:return self.reply(b'',status=416)
            raw=raw[start:end+1];status=206
        log(event='request',path=rel,status=status,bytes=len(raw))
        if status==200:return self.reply(raw,'application/vnd.apple.mpegurl' if path.suffix=='.m3u8' else 'video/mp2t')
        self.send_response(status);self.send_header('Content-Type','video/mp2t');self.send_header('Content-Length',str(len(raw)));self.send_header('Content-Range',f'bytes {start}-{end}/{size}');self.end_headers()
        try:self.wfile.write(raw)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
    def do_POST(self):
        if self.path!='/license':return self.reply(b'',status=404)
        raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
        log(event='license',mode=license_mode,requestBytes=len(raw))
        if license_mode in ('invalid','malformed'):
            return self.reply(b'not-json','application/json')
        if license_mode=='valid':
            import base64
            enc=lambda x:base64.urlsafe_b64encode(bytes.fromhex(x)).decode().rstrip('=')
            body={'keys':[{'kty':'oct','kid':enc('11223344556677889900aabbccddeeff'),'k':enc('00112233445566778899aabbccddeeff')}],'type':'temporary'}
        else:body={'keys':[],'type':'temporary'}
        return self.reply(body)
http.server.ThreadingHTTPServer(('127.0.0.1',18784),Handler).serve_forever()
