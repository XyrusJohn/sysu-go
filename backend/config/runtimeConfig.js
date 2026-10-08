const PRODUCTION_PROJECT = 'sysu-web-5a25d';
function readFirebaseConfig(env) {
 const mode=env.SYSU_ENV,projectId=env.FIREBASE_PROJECT_ID;
 const reject=()=>{throw Error('Invalid Firebase environment configuration');};
 if(!['emulator','staging','production'].includes(mode)||typeof projectId!=='string'||!/^[-a-z0-9]{6,63}$/.test(projectId))reject();
 const raw=env.FIREBASE_SERVICE_ACCOUNT_JSON||env.FIREBASE_SERVICE_ACCOUNT;
 if(mode==='emulator'){
  if(projectId!=='demo-sysu-security'||env.FIRESTORE_EMULATOR_HOST!=='127.0.0.1:8085'||env.FIREBASE_AUTH_EMULATOR_HOST!=='127.0.0.1:9099'||env.FIREBASE_STORAGE_EMULATOR_HOST||raw||env.GOOGLE_APPLICATION_CREDENTIALS)reject();
  return {mode,projectId};
 }
 if(env.FIRESTORE_EMULATOR_HOST||env.FIREBASE_AUTH_EMULATOR_HOST||env.FIREBASE_STORAGE_EMULATOR_HOST||projectId.startsWith('demo-')||env.GOOGLE_APPLICATION_CREDENTIALS)reject();
 if(mode==='production'?projectId!==PRODUCTION_PROJECT:projectId===PRODUCTION_PROJECT)reject();
 let account;try{account=JSON.parse(raw);}catch(_){reject();}
 if(!account||account.project_id!==projectId||typeof account.private_key!=='string'||!account.private_key)reject();
 account.private_key=account.private_key.replace(/\\n/g,'\n');
 return {mode,projectId,account};
}
module.exports={readFirebaseConfig};
