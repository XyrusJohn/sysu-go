const test=require('node:test'),assert=require('node:assert/strict'),http=require('node:http');
const {once}=require('node:events');const {Server}=require('socket.io');const {io:clientIo}=require('socket.io-client');
const {createSocketAuth}=require('../../middleware/socketAuth.js');

async function fixture(){
 const revoked=new Set(),profiles=new Map([['driver-a',{role:'driver',status:'active'}],['staff-a',{role:'fleet_manager',status:'active'}]]);
 const auth={verifyIdToken:async(token,checkRevoked)=>{assert.equal(checkRevoked,true);if(revoked.has(token))throw Error('revoked');return {uid:token.startsWith('synthetic-staff-')?'staff-a':'driver-a',exp:Math.floor(Date.now()/1000)+3600};}};
 const db={collection:()=>({doc:uid=>({get:async()=>{const profile=profiles.get(uid);return {exists:!!profile,data:()=>profile};}})})};
 const server=http.createServer(),io=new Server(server,{transports:['websocket']});io.use(createSocketAuth({getFirebaseAuth:()=>auth,getDb:()=>db}));
 io.on('connection',socket=>{if(['fleet_manager','admin','super_admin'].includes(socket.data.role))socket.join('staff:tracking');socket.on('driver_moving',payload=>io.to('staff:tracking').emit('driver_location',payload));});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 return {url:'http://127.0.0.1:'+server.address().port,io,server,revoked,profiles,close:async()=>new Promise(resolve=>io.close(resolve))};
}
function connect(url,token){return clientIo(url,{autoConnect:false,transports:['websocket'],reconnection:false,auth:token?{token}:{}});}

test('G13 real Socket.IO rejects missing, revoked and inactive-profile handshakes',async()=>{
 const f=await fixture();const anonymous=connect(f.url,null);try{const rejected=once(anonymous,'connect_error');anonymous.connect();const [error]=await rejected;assert.match(error.message,/authentication/i);}finally{anonymous.close();}
 const bad=connect(f.url,'synthetic-revoked-token-01');f.revoked.add('synthetic-revoked-token-01');try{const rejected=once(bad,'connect_error');bad.connect();const [error]=await rejected;assert.match(error.message,/authentication/i);}finally{bad.close();}
 f.profiles.set('driver-a',{role:'driver',status:'disabled'});const disabled=connect(f.url,'synthetic-driver-token-01');try{const rejected=once(disabled,'connect_error');disabled.connect();const [error]=await rejected;assert.match(error.message,/authentication/i);}finally{disabled.close();await f.close();}
});

test('G13 real sockets scope driver location events to staff tracking room',async()=>{
 const f=await fixture(),driver=connect(f.url,'synthetic-driver-token-01'),staff=connect(f.url,'synthetic-staff-token-01'),other=connect(f.url,'synthetic-other-driver-token-01');
 try{
  const connected=[once(driver,'connect'),once(staff,'connect'),once(other,'connect')];driver.connect();staff.connect();other.connect();await Promise.all(connected);
  const delivered=once(staff,'driver_location');driver.emit('driver_moving',{driverId:'driver-a',lat:14.6,lng:121.0});
  const [payload]=await delivered;assert.equal(payload.driverId,'driver-a');
  const leaked=await Promise.race([once(other,'driver_location').then(()=>true),new Promise(resolve=>setTimeout(()=>resolve(false),100))]);assert.equal(leaked,false);
 }finally{driver.close();staff.close();other.close();await f.close();}
});

test('G13 real socket disconnects after Auth revocation on an event',async()=>{
 const f=await fixture(),driver=connect(f.url,'synthetic-driver-token-02');try{const connected=once(driver,'connect');driver.connect();await connected;f.revoked.add('synthetic-driver-token-02');const disconnected=once(driver,'disconnect');driver.emit('heartbeat');await disconnected;assert.equal(driver.connected,false);}finally{driver.close();await f.close();}
});
