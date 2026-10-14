"""Field-by-field parity check: the original camel-session-0to1 app vs. this module's tutorial APIs.

Usage: python3 parity-check.py [OLD_BASE] [NEW_BASE]
  OLD_BASE  default http://localhost:18080            (java -jar camel-session-0to1-*.jar --server.port=18080)
  NEW_BASE  default https://localhost:8096/simple/camel
Volatile values (ids, timestamps, timings) are masked; anything else that differs is printed.
"""
import json, subprocess, sys
OLD = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18080"
NEW = sys.argv[2] if len(sys.argv) > 2 else "https://localhost:8096/simple/camel"
VOLATILE={"exchangeId","processedAt","msgId","traceId","costMs","timestamp","checkedAt","auditedAt","fulfilledAt","finishedAt"}
cases=[("/api/v1/orders/basic",{"orderId":"A-1001","amount":666,"channel":"WEB"}),
("/api/v1/orders/basic",{"amount":666}),
("/api/v1/orders/dispatch",{"orderId":"B-2001","amount":20000,"channel":"WEB"}),
("/api/v1/orders/dispatch",{"orderId":"B-2002","amount":500,"channel":"APP"}),
("/api/v1/orders/dispatch",{"orderId":"B-2003","amount":50,"channel":"WEB"}),
("/api/v1/orders/quote",{"orderId":"C-3001","amount":2000,"currency":"USD"}),
("/api/v1/orders/quote",{"orderId":"C-3002","amount":200,"currency":"EUR"}),
("/api/v1/orders/check",{"orderId":"D-4001","amount":3000}),
("/api/v1/orders/split",{"orderId":"E-5001","items":[{"sku":"BK-01","type":"BOOK","qty":2},{"sku":"FD-09","type":"FOOD","qty":1},{"sku":"XX-77","type":"TOY","qty":5}]}),
("/api/v1/orders/fulfil",{"orderId":"F-6001","amount":20000,"channel":"APP","items":[{"sku":"BK-01","type":"BOOK","qty":2},{"sku":"FD-09","type":"FOOD","qty":1}]}),
("/api/v1/orders/fulfil",{"orderId":"F-6002","amount":90000,"channel":"WEB","items":[{"sku":"BK-02","type":"BOOK","qty":1}]}),
("/api/v1/orders/accounts",{"orderId":"G-7001","userName":"ethan","channel":"APP","amount":2000}),
("/api/v1/orders/accounts/create",{"orderId":"G-7002","userName":"ethan","channel":"WEB","amount":500}),
("/api/v1/orders/accounts/detail",{"orderId":"G-7003","accountId":"ACC-G-7002"}),
("/api/v1/orders/accounts/detail",{"orderId":"G-7004","accountId":"ACC-NOPE"}),
]
def call(base,path,body):
    r=subprocess.run(["curl","-sk","-o","-","-w","\n%{http_code}","-X","POST",base+path,"-H","Content-Type: application/json","-d",json.dumps(body)],capture_output=True,text=True)
    out,code=r.stdout.rsplit("\n",1)
    try: return int(code), json.loads(out)
    except Exception: return int(code), out.strip()[:80]
def norm(x):
    if isinstance(x,dict): return {k:("<v>" if k in VOLATILE else norm(v)) for k,v in x.items()}
    if isinstance(x,list): return [norm(v) for v in x]
    return x
bad=0
for path,body in cases:
    a=call(OLD,path,body); b=call(NEW,path,body)
    same = a[0]==b[0] and (norm(a[1])==norm(b[1]) if isinstance(a[1],(dict,list)) else True)
    print(("SAME " if same else "DIFF ")+f"{a[0]}/{b[0]} {path} {body.get('orderId','-')}")
    if not same:
        bad+=1; print("   old:",json.dumps(norm(a[1]))[:400]); print("   new:",json.dumps(norm(b[1]))[:400])
# upload: compare all but volatile
import base64
up=lambda base: subprocess.run(["curl","-sk","-X","POST",base+"/api/v1/files/upload","-F","orderId=H-8001","-F","fileName=hello.txt","-F","contentType=text/plain","-F","file="+base64.b64encode(b"hello camel upload\n").decode()],capture_output=True,text=True).stdout
ua,ub=json.loads(up(OLD)),json.loads(up(NEW)); s=norm(ua)==norm(ub); bad+=0 if s else 1
print(("SAME " if s else "DIFF ")+"upload", "" if s else (norm(ua),norm(ub)))
bad64=lambda base: subprocess.run(["curl","-sk","-o","/dev/null","-w","%{http_code}","-X","POST",base+"/api/v1/files/upload","-F","orderId=H","--form-string","file=@@@notbase64@@@"],capture_output=True,text=True).stdout
codes=(bad64(OLD),bad64(NEW)); bad+=0 if codes[0]==codes[1] else 1
print(("SAME " if codes[0]==codes[1] else "DIFF ")+"invalid base64", codes)
print("differences:",bad)
