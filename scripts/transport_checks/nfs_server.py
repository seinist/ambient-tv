import os,socketserver,struct,threading
P=lambda *v:struct.pack('!'+'I'*len(v),*v)
Q=lambda v:struct.pack('!Q',v)
def opaque(b):return P(len(b))+b+b'\0'*((-len(b))%4)
def text(t):return opaque(t.encode())
class Reader:
 def __init__(self,b):self.b=b;self.i=0
 def num(self):v=struct.unpack_from('!I',self.b,self.i)[0];self.i+=4;return v
 def long(self):v=struct.unpack_from('!Q',self.b,self.i)[0];self.i+=8;return v
 def raw(self,n):v=self.b[self.i:self.i+n];self.i+=n;return v
 def opaque(self):n=self.num();v=self.raw(n);self.raw((-n)%4);return v
payload=bytes(range(251))*4000
nodes={b'root':(2,0),b'folder':(2,0),b'video':(1,len(payload)),b'audio':(1,13)}
def attrs(h):
 kind,size=nodes[h]
 return P(kind,0o755,1,65534,65534)+Q(size)+Q(size)+P(0,0)+Q(1)+Q(42)+P(100,0,200,0,300,0)
def post(h):return P(1)+attrs(h)
# NFSv4.0 pseudo filesystem: handles are paths; 'link' is a symlink the client must not follow.
tree={'/':(2,0),'/media':(2,0),'/media/folder':(2,0),'/media/Müsik.flac':(1,13),'/media/folder/sample.mp4':(1,len(payload)),'/media/link':(5,0)}
def children(p):return sorted(k[len(p.rstrip('/'))+1:] for k in tree if k!=p and k.startswith(p.rstrip('/')+'/') and '/' not in k[len(p.rstrip('/'))+1:])
def fattr(path,want,rderr=False):
 kind,size=tree[path];vals=b'';bits=0
 for bit,val in [(1,P(kind)),(4,Q(size)),(11,P(0)),(53,Q(1700000000)+P(5000000))]:
  if want>>bit&1 and (bit!=11 or rderr):bits|=1<<bit;vals+=val
 return P(2,bits&0xffffffff,bits>>32)+opaque(vals)
def bitmap(r):
 n=r.num();w=[r.num() for _ in range(n)];return sum(v<<(32*i) for i,v in enumerate(w))
def compound(r,uid):
 r.opaque();minor=r.num();n=r.num()
 if minor!=0:return P(10021)+opaque(b'')+P(0)
 cur=None;res=b'';status=0;count=0
 for _ in range(n):
  op=r.num();body=b'';status=0
  if uid==999:status=13
  elif op==24:cur='/'
  elif op==22:cur=r.opaque().decode()
  elif op==15:
   name=r.opaque().decode();nxt=cur.rstrip('/')+'/'+name
   if tree[cur][0]==5:status=10029
   elif nxt in tree:cur=nxt
   else:status=2
  elif op==10:body=opaque(cur.encode())
  elif op==9:body=fattr(cur,bitmap(r))
  elif op==26:
   cookie=r.long();r.raw(8);r.num();r.num();want=bitmap(r)
   names=children(cur);page=names[max(cookie-2,0):max(cookie-2,0)+1] if cookie else names[:1]
   start=max(cookie-2,0) if cookie else 0
   body=b'fixture!'
   for i,name in enumerate(page):body+=P(1)+Q(start+i+3)+text(name)+fattr(cur.rstrip('/')+'/'+name,want,True)
   body+=P(0,int(start+len(page)>=len(names)))
  elif op==25:
   r.raw(16);offset=r.long();count_=r.num();data=payload[offset:offset+min(count_,65536)]
   body=P(int(offset+len(data)>=len(payload)))+opaque(data)
  else:raise ValueError(op)
  count+=1;res+=P(op,status)+(body if status==0 else b'')
  if status:break
 return P(status)+opaque(b'')+P(count)+res
class Handler(socketserver.BaseRequestHandler):
 def exact(self,n):
  b=b''
  while len(b)<n:
   c=self.request.recv(n-len(b))
   if not c:raise EOFError
   b+=c
  return b
 def handle(self):
  try:
   while True:
    marker=struct.unpack('!I',self.exact(4))[0];r=Reader(self.exact(marker&0x7fffffff))
    xid=r.num();assert r.num()==0 and r.num()==2
    prog,vers,proc=r.num(),r.num(),r.num();flavor=r.num();auth=Reader(r.opaque());r.num();r.opaque()
    if flavor==1:
     auth.num();auth.opaque();uid=auth.num();gid=auth.num()
    else:uid=65534
    if prog==100000:
     assert (r.num(),r.num(),r.num(),r.num())==(100005,3,6,0);body=P(23050)
    elif prog==100005:
     assert vers==3 and proc==1 and r.opaque()==b'/media';body=P(0)+opaque(b'root')+P(1,1)
    elif prog==100003 and vers==4:
     assert proc==1;body=compound(r,uid)
    elif prog==100003:
     assert vers==3
     h=r.opaque()
     if uid==999:body=P(13)
     elif proc==1:body=P(0)+attrs(h)
     elif proc==3:
      name=r.opaque().decode()
      child={(b'root','folder'):b'folder',(b'root','Müsik.flac'):b'audio',(b'folder','sample.mp4'):b'video'}[(h,name)]
      body=P(0)+opaque(child)+post(child)+post(h)
     elif proc==17 and os.environ.get('NFS_NO_READDIRPLUS'):r.long();r.raw(8);r.num();r.num();body=P(10004,0)
     elif proc==16:
      cookie=r.long();r.raw(8);r.num()
      listing=[('folder',b'folder'),('Müsik.flac',b'audio')] if h==b'root' else [('sample.mp4',b'video')]
      page=listing[cookie:cookie+1]
      body=P(0)+post(h)+b'fixture!'
      for i,(name,ch) in enumerate(page):body+=P(1)+Q(42)+text(name)+Q(cookie+i+1)
      body+=P(0,int(cookie+len(page)>=len(listing)))
     elif proc==17:
      cookie=r.long();r.raw(8);r.num();r.num()
      listing=[('folder',b'folder'),('Müsik.flac',b'audio')] if h==b'root' else [('sample.mp4',b'video')]
      page=listing[cookie:cookie+1]
      body=P(0)+post(h)+b'fixture!'
      for i,(name,ch) in enumerate(page):body+=P(1)+Q(42)+text(name)+Q(cookie+i+1)+post(ch)+P(1)+opaque(ch)
      body+=P(0,int(cookie+len(page)>=len(listing)))
     elif proc==6:
      offset=r.long();count=r.num();data=payload[offset:offset+count]
      body=P(0)+post(h)+P(len(data),int(offset+len(data)>=len(payload)))+opaque(data)
     else:raise ValueError(proc)
    else:raise ValueError(prog)
    reply=P(xid,1,0,0,0,0)+body
    # Fragmented RPC records exercise framing independently of TCP packet boundaries.
    self.request.sendall(P(13)+reply[:13]+P(0x80000000|len(reply[13:]))+reply[13:])
  except (EOFError,ConnectionError):pass
class Server(socketserver.ThreadingTCPServer):
 allow_reuse_address=True;daemon_threads=True
servers=[Server(('127.0.0.1',p),Handler) for p in [23111,23050,23049]]
for s in servers:threading.Thread(target=s.serve_forever,daemon=True).start()
print('NFSv3 protocol fixture ready on loopback',flush=True)
threading.Event().wait()
