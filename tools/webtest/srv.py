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
            elif a=='get_live_streams': d=[{'stream_id':i,'name':n,'category_id':c,'stream_icon':'','epg_channel_id':('X.us' if i%2 else '')} for i,(n,c) in enumerate([('US| ESPN HD','1'),('US| FOX SPORTS 1','1'),('US| CNN','2'),('US| FOX NEWS','2'),('US| HISTORY','3'),('US| COMEDY CENTRAL','3'),('US| NFL REDZONE','1'),('US| NFL NETWORK','1'),('US| NBA TV','1'),('US| MLB NETWORK','1'),('US| SEC NETWORK','1')],1)]
            elif a in ('get_short_epg','get_simple_data_table'):
                b=lambda t: base64.b64encode(t.encode()).decode()
                st=now-1200; d={'epg_listings':[{'title':b('SportsCenter Live Edition'),'start_timestamp':str(st),'stop_timestamp':str(st+3600)},{'title':b('NFL Live'),'start_timestamp':str(st+3600),'stop_timestamp':str(st+7200)}]}
            else: d={}
            body=json.dumps(d).encode(); s.send_response(200); s.send_header('Content-Type','application/json'); s.send_header('Content-Length',str(len(body))); s.end_headers(); s.wfile.write(body); return
        return super().do_GET()
http.server.ThreadingHTTPServer(('127.0.0.1',8781),H).serve_forever()
