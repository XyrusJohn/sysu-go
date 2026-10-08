const express=require('express');
const {Timestamp}=require('firebase-admin/firestore');
const {getDb}=require('../config/firebaseAdmin');
const {verifyToken}=require('../middleware/auth');
const {applyPodProgress,validatePodRequest}=require('../services/podProgress');
const router=express.Router();
router.post('/:shipmentId/:obdNumber',verifyToken,async(req,res)=>{
 if(req.userProfile.role!=='driver')return res.status(403).json({success:false,message:'Driver account required'});
 try {
  const {shipmentId,obdNumber}=req.params;
  if(!/^[A-Za-z0-9_-]{1,160}$/.test(shipmentId)||!/^[-A-Za-z0-9_. ]{1,100}$/.test(obdNumber))return res.status(400).json({success:false,message:'Invalid shipment or OBD'});
  const now=Date.now(),input=validatePodRequest(req.body,now),db=getDb();
  const ref=db.collection('sap_assignments').doc(shipmentId),imageRef=ref.collection('pod_images').doc(obdNumber);
  await db.runTransaction(async tx=>{
   const snap=await tx.get(ref);
   if(!snap.exists){const error=new Error('Shipment not found');error.status=404;throw error;}
   const rows=applyPodProgress(snap.data(),req.user.uid,obdNumber,input,now).map(row=>row.obdNumber===obdNumber&&typeof row.podCapturedAt==='number'?{...row,podCapturedAt:Timestamp.fromMillis(row.podCapturedAt)}:row);
   tx.update(ref,{lineItems:rows});
   if(input.action==='complete')tx.set(imageRef,{base64Image:input.base64Image,capturedAt:Timestamp.fromMillis(input.capturedAtMillis),recordedAt:Timestamp.fromMillis(now),driverId:req.user.uid,obdNumber});
   if(input.action==='retake')tx.delete(imageRef);
  });
  req.app.get('io')?.to('staff:tracking').emit('pod_updated',{shipmentDocId:shipmentId,driverId:req.user.uid,source:'driver_app'});
  return res.json({success:true,obdNumber,capturedAtMillis:input.capturedAtMillis??null});
 }catch(error){return res.status(error.status||500).json({success:false,message:error.status?error.message:'Unable to save POD. Please retry.'});}
});
module.exports=router;
