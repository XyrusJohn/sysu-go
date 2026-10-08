// Loads the shipped server/routes with synthetic Firebase, no dotenv, and no
// outbound monitors. Only the test starts its HTTP listener, on loopback.
const fs = require('node:fs'), path = require('node:path'), Module = require('node:module');
const http = require('node:http');
async function serverFixture() {
  const root = path.resolve(__dirname, '../..'), modules = new Map(), writes = [];
  const revoked = new Set(), profiles = new Map();
  const tokens = new Map();
  for (const [uid, role] of [['driver-a','driver'],['driver-b','driver'],['staff-a','fleet_manager'],['qr-a','qr_generator']]) {
    const token = 'synthetic-' + uid + '-token-0001'; tokens.set(token,uid); profiles.set(uid,{role,status:'active'});
  }
  const records = new Map([
    ['drivers/driver-a',{name:'Canonical A',status:'online'}], ['drivers/driver-b',{name:'Canonical B',status:'online'}],
    ['sap_assignments/own-a',{driverId:'driver-a',tripStatus:'in_transit'}],
    ['sap_assignments/foreign-b',{driverId:'driver-b',tripStatus:'in_transit'}],
  ]);
  const state = {writeError:false, readError:false};
  const db = {collection: collection => {
    const constraints = [];
    const query = {
      doc: uid => ({
        get: async () => {
          if (state.readError) throw Error('private synthetic read failure');
          const data = collection === 'users' ? profiles.get(uid) : records.get(collection+'/'+uid);
          return {exists:!!data, data:()=>data};
        },
        set: async (payload, options) => {
          if (state.writeError) throw Error('private synthetic write failure');
          const key=collection+'/'+uid; writes.push({key,payload});
          records.set(key,options?.merge?{...records.get(key),...payload}:payload);
        },
      }),
      where: (field,op,value) => {constraints.push([field,op,value]); return query;},
      limit: () => query,
      get: async () => {
        if(state.readError) throw Error('private synthetic query failure');
        const docs = [...records].filter(([key,data])=>key.startsWith(collection+'/')&&constraints.every(([field,op,value])=>op==='=='?data[field]===value:op==='not-in'?!value.includes(data[field]):true)).map(([key,data])=>({id:key.split('/')[1],exists:true,data:()=>data}));
        return {empty:!docs.length,docs,size:docs.length};
      },
    }; return query;
  }};
  const auth = {verifyIdToken:async(token, checkRevoked)=>{
    if(checkRevoked!==true || !tokens.has(token) || revoked.has(token)) throw Error('private invalid token');
    return {uid:tokens.get(token),exp:Math.floor(Date.now()/1000)+3600};
  }};
  let listeningServer;
  const overrides = new Map([
    [path.join(root,'config/firebaseAdmin.js'),{getDb:()=>db,getFirebaseAuth:()=>auth}],
    [path.join(root,'utils/heartbeatMonitor.js'),{startHeartbeat(){}}],
    [path.join(root,'utils/presenceWatcher.js'),{startPresenceWatcher(){}}],
  ]);
  function load(filename) {
    if(overrides.has(filename)) return overrides.get(filename);
    if(modules.has(filename)) return modules.get(filename).exports;
    const module = new Module(filename); module.filename=filename; modules.set(filename,module);
    const normalRequire=Module.createRequire(filename);
    module.require=specifier=>{
      if(specifier==='dotenv') return {config(){}};
      if(specifier==='http' && filename===path.join(root,'server.js')) return {...http,createServer:app=>{
        listeningServer=http.createServer(app); listeningServer.listen=()=>listeningServer; return listeningServer;
      }};
      if(specifier.startsWith('.')) return load(normalRequire.resolve(specifier));
      return normalRequire(specifier);
    };
    const extra = filename===path.join(root,'server.js')?'\nmodule.exports={app,server,io};':'';
    module._compile(fs.readFileSync(filename,'utf8')+extra,filename); return module.exports;
  }
  const {app,server,io}=load(path.join(root,'server.js'));
  server.listen=http.Server.prototype.listen;
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  return {app,server,io,url:'http://127.0.0.1:'+server.address().port,records,profiles,revoked,tokens,writes,state,
    token:uid=>[...tokens].find(([,value])=>value===uid)[0],
    close:()=>new Promise(resolve=>io.close(resolve))};
}
module.exports={serverFixture};
