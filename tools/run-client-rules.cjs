const path = require('node:path');
const {spawnSync} = require('node:child_process');
const mobile = path.resolve(__dirname,'..');
const web = path.resolve(mobile,'../sysu-app');
const node = process.execPath;
if (Number(process.versions.node.split('.')[0]) !== 24) throw Error('Use Node 24');
if (process.argv[2] === '--inside') {
  if (process.env.GCLOUD_PROJECT !== 'demo-sysu-security' || process.env.FIRESTORE_EMULATOR_HOST !== '127.0.0.1:8085') {
    throw Error('Local demo emulator required');
  }
  for (const target of ['mobile','web']) {
    const run = spawnSync(node,['--test',path.join(mobile,'backend/tests/integration/clientRules.spec.mjs')],
      {env:{...process.env,RULES_TARGET:target},stdio:'inherit'});
    console.log('Shared policy target: '+target+'; exit: '+run.status);
    if (run.status !== 0) process.exit(run.status ?? 1);
  }
} else {
  const java = process.env.JAVA_HOME || path.join(web,'.tools/java21/jdk-21.0.12.1+1-jre');
  const env = {...process.env,JAVA_HOME:java,PATH:path.join(java,'bin')+';'+process.env.PATH,
    GCLOUD_PROJECT:'demo-sysu-security',FIRESTORE_EMULATOR_HOST:'127.0.0.1:8085',FIREBASE_AUTH_EMULATOR_HOST:'127.0.0.1:9099'};
  delete env.FIREBASE_SERVICE_ACCOUNT_JSON;
  delete env.FIREBASE_SERVICE_ACCOUNT;
  delete env.GOOGLE_APPLICATION_CREDENTIALS;
  const command='"'+node.replaceAll('\\','/')+'" "'+__filename.replaceAll('\\','/')+'" --inside';
  const run=spawnSync(node,[path.join(web,'backend/node_modules/firebase-tools/lib/bin/firebase.js'),
    'emulators:exec','--config',path.join(web,'firebase.test.json'),'--project','demo-sysu-security',
    '--only','auth,firestore',command],{cwd:web,env,stdio:'inherit'});
  process.exitCode=run.status ?? 1;
}
