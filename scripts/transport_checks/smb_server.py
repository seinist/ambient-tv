from pathlib import Path
import tempfile
fixture = tempfile.TemporaryDirectory(prefix="tv-smb-check-")
from impacket import smbserver
from impacket.ntlm import compute_lmhash,compute_nthash
root=Path(fixture.name)/'share';root.mkdir(exist_ok=True)
(root/'folder').mkdir(exist_ok=True)
(root/'folder'/'sample.mp4').write_bytes(bytes(range(251))*4000)
(root/'track.flac').write_bytes(b'FLAC test payload')
server=smbserver.SimpleSMBServer(listenAddress='127.0.0.1',listenPort=14450)
server.addShare('media',str(root.resolve()),'read fixture',readOnly='yes')
server.setSMB2Support(True)
server.addCredential('testuser',0,compute_lmhash('fixture-secret'),compute_nthash('fixture-secret'))
server.setLogFile(str((Path(fixture.name)/'smb-server.log').resolve()))
print('SMB2 fixture ready on loopback 14450',flush=True)
server.start()
