# Mock Xtream Codes server for the web app tests: serves docs/app and a tiny fake channel list and guide.
# Run: python3 tools/webtest/srv.py  (http://127.0.0.1:8781)
import http.server, json, time, base64, urllib.parse, os, sys
ROOT=os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','..','docs','app')
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(s,*a,**k): super().__init__(*a, directory=ROOT, **k)
    def log_message(s,*a): pass
    def do_GET(s):
        u=urllib.parse.urlparse(s.path)
        if u.path.endswith('player_api.php'):
            q=urllib.parse.parse_qs(u.query); a=q.get('action',[''])[0]; now=int(time.time())
            if a=='': d={'user_info':{'auth':1,'status':'Active','allowed_output_formats':['m3u8','ts']}}
            elif a=='get_live_categories': d=[{'category_id':'1','category_name':'US| SPORTS'},{'category_id':'2','category_name':'US| NEWS'},{'category_id':'3','category_name':'US| ENTERTAINMENT'}]
            elif a=='get_live_streams' and q.get('username',[''])[0]=='qf': d=QF_CHANNELS
            elif a=='get_live_streams': d=[{'stream_id':i,'name':n,'category_id':c,'stream_icon':'','epg_channel_id':('X.us' if i%2 else '')} for i,(n,c) in enumerate([('US| ESPN HD','1'),('US| FOX SPORTS 1','1'),('US| CNN','2'),('US| FOX NEWS','2'),('US| HISTORY','3'),('US| COMEDY CENTRAL','3'),('US| NFL REDZONE','1'),('US| NFL NETWORK','1'),('US| NBA TV','1'),('US| MLB NETWORK','1'),('US| SEC NETWORK','1')],1)]
            elif a in ('get_short_epg','get_simple_data_table'):
                b=lambda t: base64.b64encode(t.encode()).decode()
                st=now-1200; d={'epg_listings':[{'title':b('SportsCenter Live Edition'),'start_timestamp':str(st),'stop_timestamp':str(st+3600)},{'title':b('NFL Live'),'start_timestamp':str(st+3600),'stop_timestamp':str(st+7200)}]}
            else: d={}
            body=json.dumps(d).encode(); s.send_response(200); s.send_header('Content-Type','application/json'); s.send_header('Content-Length',str(len(body))); s.end_headers(); s.wfile.write(body); return
        # Quality fallback test channels (username qf): real HLS streams from mkstreams.sh, served as live.
        if u.path.startswith('/live/qf/'):
            sid=u.path.rsplit('/',1)[-1].split('.')[0]
            name=QF_STREAMS.get(sid)
            if name=='multi': return s.text(QF_MASTER,'application/vnd.apple.mpegurl')
            if name: return s.text(live_playlist(name),'application/vnd.apple.mpegurl')
        if u.path.startswith('/qf/'):
            parts=u.path.split('/')
            if len(parts)==4 and parts[3]=='live.m3u8': return s.text(live_playlist(parts[2]),'application/vnd.apple.mpegurl')
            if len(parts)==4 and parts[2] in ('uhd','fhd') and parts[3].replace('.','').replace('_','').isalnum():
                f=os.path.join(STREAMS,parts[2],parts[3])
                if os.path.isfile(f):
                    body=open(f,'rb').read(); s.send_response(200); s.send_header('Content-Type','video/iso.segment' if f.endswith('.m4s') else 'video/mp4')
                    s.send_header('Content-Length',str(len(body))); s.send_header('Cache-Control','no-store'); s.end_headers(); s.wfile.write(body); return
            s.send_response(404); s.end_headers(); return
        return super().do_GET()
    def text(s,t,ct):
        body=t.encode(); s.send_response(200); s.send_header('Content-Type',ct); s.send_header('Content-Length',str(len(body))); s.send_header('Cache-Control','no-store'); s.end_headers(); s.wfile.write(body)
STREAMS=os.path.join(os.path.dirname(os.path.abspath(__file__)),'streams')
QF_CHANNELS=[{'stream_id':i,'name':n,'category_id':'1','stream_icon':'','epg_channel_id':''} for i,n in [(101,'US| ESPN 4K'),(102,'US| ESPN FHD'),(103,'US| FOX 4K'),(104,'US| CNN')]]
QF_STREAMS={'101':'uhd','102':'fhd','103':'multi','104':'fhd'}
QF_MASTER='#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=6600000,RESOLUTION=3840x2160,CODECS="vp09.00.10.08"\n/qf/uhd/live.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1700000,RESOLUTION=1920x1080,CODECS="vp09.00.10.08"\n/qf/fhd/live.m3u8\n'
T0=time.time()
def live_playlist(name):
    # A live window of 6 segments that moves with the clock and loops over the 20 files (with a discontinuity).
    n=len([f for f in os.listdir(os.path.join(STREAMS,name)) if f.endswith('.m4s')]) if os.path.isdir(os.path.join(STREAMS,name)) else 0
    if not n: return '#EXTM3U\n'
    k=int((time.time()-T0)/2)
    out=['#EXTM3U','#EXT-X-VERSION:7','#EXT-X-TARGETDURATION:2','#EXT-X-MEDIA-SEQUENCE:%d'%k,'#EXT-X-DISCONTINUITY-SEQUENCE:%d'%(k//n),'#EXT-X-MAP:URI="/qf/%s/init.mp4"'%name]
    for j in range(k,k+6):
        if j%n==0 and j>k: out+=['#EXT-X-DISCONTINUITY','#EXT-X-MAP:URI="/qf/%s/init.mp4"'%name]
        out+=['#EXTINF:2.000,','/qf/%s/seg_%03d.m4s'%(name,j%n)]
    return '\n'.join(out)+'\n'
http.server.ThreadingHTTPServer(('127.0.0.1',8781),H).serve_forever()
