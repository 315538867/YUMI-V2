window.YumiOrderStore=(()=>{
  const storageKey='yumi-v2-order-finance-v1';
  const seedConfigs=[
    {no:'OD-20261001-08',customerId:1,customerName:'花间礼物',orderDate:'2026-10-01',expectedDeliveryDate:'2026-10-12',status:'IN_FULFILLMENT',paid:'2000.00',note:'分批交付，先送到店内验收。',lines:[{productId:1,quantity:100,unitPrice:'58.00',qualified:65,shipped:40}]},
    {no:'OD-20261006-03',customerId:2,customerName:'山岚手作',orderDate:'2026-10-06',expectedDeliveryDate:'2026-10-20',status:'CONFIRMED',lines:[{productId:1,schemeId:101,quantity:30,unitPrice:'50.00'},{productId:2,quantity:20,unitPrice:'46.00'}]},
    {no:'OD-20261005-02',customerId:3,customerName:'拾光集合店',orderDate:'2026-10-05',expectedDeliveryDate:'2026-10-15',status:'IN_FULFILLMENT',paid:'1360.00',note:'款项已收足，仍需完成交付。',lines:[{productId:3,quantity:20,unitPrice:'68.00',qualified:8}]},
    {no:'OD-20261002-01',customerId:4,customerName:'青木工作室',orderDate:'2026-10-02',expectedDeliveryDate:'2026-10-07',status:'IN_FULFILLMENT',paid:'500.00',activityDate:'2026-10-07',note:'已交付，尾款待收。',lines:[{productId:4,quantity:30,unitPrice:'39.00',qualified:30,shipped:30}]},
    {no:'OD-20260928-04',customerId:1,customerName:'花间礼物',orderDate:'2026-09-28',expectedDeliveryDate:'2026-10-06',status:'IN_FULFILLMENT',paid:'920.00',lines:[{productId:2,quantity:40,unitPrice:'46.00',qualified:25,shipped:20}]},
    {no:'OD-20260925-02',customerId:3,customerName:'拾光集合店',orderDate:'2026-09-25',expectedDeliveryDate:'2026-10-05',status:'COMPLETED',paid:'1250.00',refunded:'90.00',activityDate:'2026-10-05',lines:[{productId:1,quantity:20,unitPrice:'58.00',qualified:20,shipped:20}]},
    {no:'OD-20260920-01',customerId:5,customerName:'旧时小店',orderDate:'2026-09-20',expectedDeliveryDate:'2026-10-03',status:'CANCELLED',paid:'390.00',refunded:'390.00',activityDate:'2026-10-06',lines:[{productId:4,quantity:10,unitPrice:'39.00'}]}
  ];
  const clone=value=>JSON.parse(JSON.stringify(value));
  function today(){return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date())}
  function recordedAt(){return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date())}
  function validDate(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value))return false;const date=new Date(value+'T00:00:00Z');return Number.isFinite(date.getTime())&&date.toISOString().slice(0,10)===value}
  function cents(value){const [whole,fraction='']=value.split('.');return Number(whole)*100+Number(fraction.padEnd(2,'0'))}
  function seedFact(config,type,amount,sequence){const id='CF-'+config.no.slice(3)+'-'+sequence;const orderId=config.no;return{id,no:id,orderId,type,direction:type==='ORDER_RECEIPT'?'IN':'OUT',businessDate:config.activityDate||'2026-10-07',recordedAt:(config.activityDate||'2026-10-07')+' 10:00:00',amountCents:cents(amount),valid:true,note:type==='ORDER_RECEIPT'?'订单关联收款示例':config.status==='CANCELLED'?'取消订单后退款':'原多收款退回示例',allocations:[{sourceId:type==='ORDER_RECEIPT'?orderId:'RF-'+orderId.slice(3),amountCents:cents(amount)}]}}
  const initialFacts=seedConfigs.flatMap(config=>[...(config.paid?[seedFact(config,'ORDER_RECEIPT',config.paid,'01')]:[]),...(config.refunded?[seedFact(config,'ORDER_REFUND',config.refunded,'02')]:[])]);
  const initialShippingFacts=seedConfigs.flatMap(config=>{
    const lines=config.lines.flatMap((line,index)=>line.shipped?[{itemId:`${config.no}-I${index+1}`,sourceId:`QS-D-${config.no.slice(3)}-${index+1}`,quantity:line.shipped}]:[]);
    if(!lines.length)return[];
    const current={no:'SH-'+config.no.slice(3),orderId:config.no,date:config.activityDate||'2026-10-07',recordedAt:(config.activityDate||'2026-10-07')+' 10:00:00',carrier:'顺丰',trackingNo:'SF000000000001',note:'示例物流单号',lines,voided:false,supersedesNo:'',reason:''};
    if(config.no!=='OD-20260925-02')return[current];
    const old={...clone(current),no:current.no+'-OLD',date:'2026-10-04',recordedAt:'2026-10-04 10:00:00',trackingNo:'SF000000000000',lines:[{...lines[0],quantity:18}],note:'数量录入错误，已由后续记录更正'};
    return[old,{...current,supersedesNo:old.no,reason:'实际已发货 20 件，更正原录入数量'}];
  });
  let state={version:1,customOrders:[],facts:initialFacts,shippingFacts:clone(initialShippingFacts)};
  const subscribers=[];
  function metadata(){return[...seedConfigs.map(config=>({no:config.no,customerName:config.customerName,status:config.status,orderDate:config.orderDate,receivableCents:config.status==='CANCELLED'?0:config.lines.reduce((total,line)=>total+line.quantity*cents(line.unitPrice),0)})),...state.customOrders.map(order=>({no:order.no,customerName:order.customerSnapshot.name,status:order.status,orderDate:order.orderDate,receivableCents:order.receivableCents}))]}
  function decode(text){
    try{
      if(typeof text!=='string'||text.length>3*1024*1024)return null;
      const value=JSON.parse(text);
      if(value?.version!==1||!Array.isArray(value.customOrders)||!Array.isArray(value.facts))return null;
      if(!value.customOrders.every(order=>/^OD-\d{8}-\d+$/.test(order.no)&&order.id===order.no&&validDate(order.orderDate)&&Number.isSafeInteger(order.receivableCents)&&order.receivableCents>=0&&order.customerSnapshot?.name&&Array.isArray(order.items)&&Array.isArray(order.events)&&Array.isArray(order.shipments)&&['CONFIRMED','IN_FULFILLMENT','COMPLETED','CANCELLED'].includes(order.status)))return null;
      if(!value.customOrders.every(order=>shippingOrder(order.no,value.customOrders)))return null;
      const knownOrders=new Set([...seedConfigs.map(order=>order.no),...value.customOrders.map(order=>order.no)]);
      if(!value.facts.every(fact=>typeof fact.id==='string'&&fact.no===fact.id&&knownOrders.has(fact.orderId)&&['ORDER_RECEIPT','ORDER_REFUND'].includes(fact.type)&&['IN','OUT'].includes(fact.direction)&&validDate(fact.businessDate)&&Number.isSafeInteger(fact.amountCents)&&fact.amountCents>0&&typeof fact.note==='string'&&Array.isArray(fact.allocations)&&fact.allocations.length===1&&fact.allocations[0].amountCents===fact.amountCents&&fact.allocations[0].sourceId===(fact.type==='ORDER_RECEIPT'?fact.orderId:'RF-'+fact.orderId.slice(3))))return null;
      if(new Set(value.facts.map(fact=>fact.id)).size!==value.facts.length)return null;
      if(!value.facts.every(fact=>{if(!fact.reversesId)return fact.direction===(fact.type==='ORDER_RECEIPT'?'IN':'OUT');const original=value.facts.find(row=>row.id===fact.reversesId);return original&&!original.reversesId&&original.orderId===fact.orderId&&original.type===fact.type&&original.amountCents===fact.amountCents&&original.direction!==fact.direction}))return null;
      if(window.YumiPaymentVouchers.capacity(value.facts))return null;
      if(value.shippingFacts===undefined)value.shippingFacts=clone(initialShippingFacts);
      if(!shippingFactsValid(value.shippingFacts,value.customOrders))return null;
      return value;
    }catch{return null}
  }
  function shippingOrder(no,customOrders=state.customOrders){
    const seed=seedConfigs.find(order=>order.no===no);
    const custom=customOrders.find(order=>order.no===no);
    if(!seed&&!custom)return null;
    if(custom&&(!custom.items.length||custom.items.length>20||new Set(custom.items.map(item=>item?.id)).size!==custom.items.length||!custom.items.every(item=>item&&typeof item.id==='string'&&typeof item.agreementId==='string'&&Number.isSafeInteger(item.activeQuantity)&&item.activeQuantity>=0&&item.activeQuantity<=999999&&typeof item.agreement?.targetProcess==='string'&&Array.isArray(item.productSnapshot?.processes)&&item.productSnapshot.processes.includes(item.agreement.targetProcess)&&typeof item.demand?.no==='string'&&Number.isSafeInteger(item.demand.qualifiedQuantity)&&item.demand.qualifiedQuantity>=0&&item.demand.qualifiedQuantity<=999999&&(!item.demand.qualifiedDate||(validDate(item.demand.qualifiedDate)&&item.demand.qualifiedDate>=custom.orderDate)))))return null;
    const order=seed||custom;
    const items=seed?seed.lines.map((line,index)=>({id:`${no}-I${index+1}`,agreementId:`${no}-A${index+1}`,activeQuantity:seed.status==='CANCELLED'?0:line.quantity,targetProcess:line.schemeId===101?'捏毛装袋':({1:'缝边剪袋',2:'缝边剪袋',3:'捏毛装袋',4:'制作'})[line.productId],demand:{no:`D-${no.slice(3)}-${index+1}`,qualifiedQuantity:line.qualified||0,qualifiedDate:seed.no==='OD-20260925-02'?'2026-10-04':seed.activityDate||'2026-10-07'}})):custom.items;
    return{no,status:order.status,orderDate:order.orderDate,items:items.map(item=>({id:item.id,agreementId:item.agreementId,activeQuantity:item.activeQuantity,targetProcess:item.targetProcess||item.agreement?.targetProcess,source:{id:'QS-'+item.demand.no,demandNo:item.demand.no,agreementId:item.agreementId,process:item.targetProcess||item.agreement?.targetProcess,quality:'QUALIFIED',qualifiedQuantity:item.demand.qualifiedQuantity||0,availableDate:item.demand.qualifiedDate||order.orderDate}}))};
  }
  function effectiveShipping(records){const replaced=new Set(records.map(record=>record.supersedesNo).filter(Boolean));return records.filter(record=>!replaced.has(record.no)&&!record.voided)}
  function shippedFor(records,itemId){return effectiveShipping(records).reduce((total,record)=>total+record.lines.filter(line=>line.itemId===itemId).reduce((quantity,line)=>quantity+line.quantity,0),0)}
  function shippingFactsValid(records,customOrders){
    if(!Array.isArray(records)||records.length>1500)return false;
    const prefix=[];const requests=new Set();
    for(const record of records){
      const order=shippingOrder(record.orderId,customOrders);
      if(!order||typeof record.no!=='string'||!/^SH-[A-Za-z0-9-]+$/.test(record.no)||prefix.some(current=>current.no===record.no)||!validDate(record.date)||record.date<order.orderDate||typeof record.recordedAt!=='string')return false;
      if(record.requestId!==undefined&&(typeof record.requestId!=='string'||!record.requestId||record.requestId.length>120||requests.has(record.requestId)))return false;
      if(record.requestId)requests.add(record.requestId);
      if(typeof record.carrier!=='string'||record.carrier.length>40||typeof record.trackingNo!=='string'||record.trackingNo.length>80||typeof record.note!=='string'||record.note.length>200||typeof record.reason!=='string'||record.reason.length>200||typeof record.supersedesNo!=='string'||!Array.isArray(record.lines)||record.lines.length>20||typeof record.voided!=='boolean')return false;
      if(record.supersedesNo){const original=prefix.find(current=>current.no===record.supersedesNo);if(!original||original.orderId!==record.orderId||prefix.some(current=>current.supersedesNo===original.no)||typeof record.reason!=='string'||!record.reason.trim()||record.reason.length>200)return false}
      if(record.voided?(!record.supersedesNo||record.lines.length!==0):record.lines.length===0)return false;
      if(new Set(record.lines.map(line=>line.itemId)).size!==record.lines.length)return false;
      for(const line of record.lines){const item=order.items.find(current=>current.id===line.itemId);if(!item||item.source.id!==line.sourceId||!item.targetProcess||item.source.process!==item.targetProcess||item.source.quality!=='QUALIFIED'||!Number.isSafeInteger(line.quantity)||line.quantity<1||line.quantity>999999||record.date<item.source.availableDate)return false}
      const next=[...prefix.filter(current=>current.orderId===order.no),record];
      if(order.items.some(item=>{const quantity=shippedFor(next,item.id);return quantity>item.activeQuantity||quantity>item.source.qualifiedQuantity}))return false;
      prefix.push(record);
    }
    return true;
  }
  function shippingView(no){
    const order=shippingOrder(no);if(!order)return null;
    const records=state.shippingFacts.filter(record=>record.orderId===no);
    const replaced=new Map(records.filter(record=>record.supersedesNo).map(record=>[record.supersedesNo,record.no]));
    const items=order.items.map(item=>{const shippedQuantity=shippedFor(records,item.id);return{...item,shippedQuantity,availableQuantity:Math.max(0,Math.min(item.activeQuantity-shippedQuantity,item.source.qualifiedQuantity-shippedQuantity))}});
    let status=order.status;
    if(status==='COMPLETED'&&records.some((record,index)=>record.requestId&&record.supersedesNo&&order.items.some(item=>shippedFor(records.slice(0,index+1),item.id)<item.activeQuantity)))status='IN_FULFILLMENT';
    if(status==='CONFIRMED'&&effectiveShipping(records).length)status='IN_FULFILLMENT';
    const shipments=records.map(record=>({...record,valid:!replaced.has(record.no)&&!record.voided,supersededBy:replaced.get(record.no)||''})).reverse();
    const events=records.filter(record=>record.requestId).map(record=>({id:record.no+'-EVENT',date:record.date,title:record.supersedesNo?'发货更正':'发货确认',description:record.supersedesNo?`${record.reason}；更正 ${record.supersedesNo}，保留原记录${record.voided?'，撤回该次发货':''}。`:`本次发货 ${record.lines.reduce((total,line)=>total+line.quantity,0)} 件，消费合格示例来源；收款与订单完成状态不自动变更。`})).reverse();
    return clone({no,status,items,shipments,events});
  }
  function shippingFingerprint(no){return JSON.stringify({order:shippingOrder(no),facts:state.shippingFacts.filter(record=>record.orderId===no)})}
  function shipmentPayload(input){return{orderId:input.orderId,date:input.date,carrier:typeof input.carrier==='string'?input.carrier.trim():input.carrier,trackingNo:typeof input.trackingNo==='string'?input.trackingNo.trim():input.trackingNo,note:typeof input.note==='string'?input.note.trim():input.note,lines:clone(input.lines||[]),supersedesNo:input.supersedesNo||'',reason:typeof input.reason==='string'?input.reason.trim():input.reason||'',voided:input.voided===true}}
  function shipmentError(input){
    const order=shippingOrder(input.orderId);if(!order||order.status==='CANCELLED')return'已取消或不存在的订单不能登记发货';
    if(!input.supersedesNo&&shippingView(input.orderId).status==='COMPLETED')return'已完成订单不能新增发货，请使用发货更正';
    if(!validDate(input.date)||input.date<order.orderDate||input.date>today())return'发货日期无效，不能早于订单日期或晚于今天';
    if(typeof input.carrier!=='string'||input.carrier.length>40||typeof input.trackingNo!=='string'||input.trackingNo.length>80||typeof input.note!=='string'||input.note.length>200)return'物流公司最多 40 字、物流单号最多 80 字、备注最多 200 字';
    if(typeof input.requestId!=='string'||!input.requestId||input.requestId.length>120)return'请重新发起发货操作';
    if(!Array.isArray(input.lines))return'请填写有效的发货明细';
    if(input.supersedesNo&&(!input.reason||typeof input.reason!=='string'||!input.reason.trim()||input.reason.length>200))return'请填写更正原因，最多 200 字';
    const record={...shipmentPayload(input),no:'SH-CHECK',recordedAt:recordedAt(),requestId:input.requestId};
    if(!shippingFactsValid([...state.shippingFacts,record],state.customOrders))return'发货明细或原记录已变化：只能使用本订单达到目标工序的合格来源，数量不能超过当前可发量';
    return'';
  }
  function validateShipment(input){refresh();return shipmentError(input)}
  function appendShipment(input){
    refresh();
    const existing=state.shippingFacts.find(record=>record.requestId===input.requestId);
    if(existing){if(JSON.stringify(shipmentPayload(existing))!==JSON.stringify(shipmentPayload(input)))throw new Error('同一发货请求内容不一致');return clone(existing)}
    const error=shipmentError(input);if(error)throw new Error(error);
    if(!input.expectedFingerprint||input.expectedFingerprint!==shippingFingerprint(input.orderId))throw new Error('订单发货或来源可发量已变化，请重新核对');
    let sequence=1;let no;do{no='SH-'+today().replaceAll('-','')+'-R'+String(sequence++).padStart(3,'0')}while(state.shippingFacts.some(record=>record.no===no));
    const record={...shipmentPayload(input),no,requestId:input.requestId,recordedAt:recordedAt()};
    const next={...state,shippingFacts:[...state.shippingFacts,record]};
    if(!decode(JSON.stringify(next)))throw new Error('发货示例数据超出可保存范围');
    try{window.localStorage.setItem(storageKey,JSON.stringify(next))}catch{throw new Error('无法保存发货记录，请检查浏览器存储空间；本次发货尚未登记')}
    state=next;notify();return clone(record);
  }
  function stored(){try{return decode(window.localStorage.getItem(storageKey))}catch{return null}}
  function merge(incoming){if(!incoming)return;for(const order of incoming.customOrders){if(!seedConfigs.some(seed=>seed.no===order.no)&&!state.customOrders.some(current=>current.no===order.no))state.customOrders.push(clone(order))}for(const fact of incoming.facts){if(!state.facts.some(current=>current.id===fact.id))state.facts.push(clone(fact))}for(const record of incoming.shippingFacts){if(!state.shippingFacts.some(current=>current.no===record.no)&&shippingFactsValid([...state.shippingFacts,record],state.customOrders))state.shippingFacts.push(clone(record))}}
  function notify(){subscribers.forEach(callback=>callback())}
  function persist(){try{window.localStorage.setItem(storageKey,JSON.stringify(state))}catch{}notify()}
  function refresh(){merge(stored());notify()}
  const navigation=new URLSearchParams((window.location?.hash||'').slice(1));
  merge(stored());merge(decode(navigation.get('orderState')));persist();
  function facts(){return clone(state.facts)}
  function orderMetadata(no){return metadata().find(order=>order.no===no)}
  function totals(no){const records=state.facts.filter(fact=>fact.orderId===no&&fact.valid);const receivedCents=records.filter(fact=>fact.type==='ORDER_RECEIPT').reduce((total,fact)=>total+fact.amountCents*(fact.reversesId?-1:1),0);const refundedCents=records.filter(fact=>fact.type==='ORDER_REFUND').reduce((total,fact)=>total+fact.amountCents*(fact.reversesId?-1:1),0);return{receivedCents,refundedCents,netReceivedCents:receivedCents-refundedCents}}
  function remaining(no){const order=orderMetadata(no);return order&&order.status!=='CANCELLED'?Math.max(0,order.receivableCents-totals(no).netReceivedCents):0}
  function receiptFingerprint(no){return JSON.stringify({order:orderMetadata(no),facts:state.facts.filter(fact=>fact.orderId===no)})}
  function nextFactId(){let sequence=1;let id;do{id='CF-'+today().replaceAll('-','')+'-R'+String(sequence++).padStart(3,'0')}while(state.facts.some(fact=>fact.id===id));return id}
  function commitFact(fact){
    const next={...state,facts:[...state.facts,fact]};
    const error=window.YumiPaymentVouchers.capacity(next.facts);if(error)throw new Error(error);
    if(next.facts.some(record=>record.vouchers?.length)){try{window.localStorage.setItem(storageKey,JSON.stringify(next))}catch{throw new Error('无法保存本地凭证，请检查浏览器存储空间后重试；本次记录尚未登记')}}
    state.facts.push(fact);persist();return clone(fact);
  }
  function appendReceipt(input){
    refresh();
    const vouchers=window.YumiPaymentVouchers.copy(input.vouchers);
    if(typeof input.requestId!=='string'||!input.requestId)throw new Error('请重新发起收款');
    if(typeof input.note!=='string'||input.note.length>200)throw new Error('收款说明最多 200 个字符');
    const existing=state.facts.find(fact=>fact.requestId===input.requestId);
    if(existing){if(existing.orderId!==input.orderId||existing.amountCents!==input.amountCents||existing.businessDate!==input.businessDate||existing.note!==input.note.trim()||JSON.stringify(existing.vouchers||[])!==JSON.stringify(vouchers))throw new Error('同一收款请求内容不一致');return clone(existing)}
    const order=orderMetadata(input.orderId);
    if(!order||order.status==='CANCELLED'||remaining(input.orderId)<=0)throw new Error('订单当前没有有效待收款');
    if(!Number.isSafeInteger(input.amountCents)||input.amountCents<=0||input.amountCents>99999999||input.amountCents>remaining(input.orderId))throw new Error('实际收款金额须大于零且不能超过订单待收金额');
    if(!validDate(input.businessDate)||input.businessDate<order.orderDate||input.businessDate>today())throw new Error('收款日期无效，不能早于订单或晚于今天');
    if(input.expectedFingerprint&&input.expectedFingerprint!==receiptFingerprint(input.orderId))throw new Error('订单收款情况已变化，请重新核对');
    const id=nextFactId();const fact={id,no:id,orderId:order.no,type:'ORDER_RECEIPT',direction:'IN',businessDate:input.businessDate,recordedAt:recordedAt(),amountCents:input.amountCents,valid:true,note:input.note.trim(),requestId:input.requestId,vouchers,allocations:[{sourceId:order.no,amountCents:input.amountCents}]};
    return commitFact(fact);
  }
  function appendRefund(input){
    refresh();
    const vouchers=window.YumiPaymentVouchers.copy(input.vouchers);
    if(typeof input.note!=='string'||input.note.length>200)throw new Error('退款说明最多 200 个字符');
    if(input.requestId!==undefined&&(typeof input.requestId!=='string'||!input.requestId))throw new Error('请重新发起退款');
    const existing=input.requestId&&state.facts.find(fact=>fact.requestId===input.requestId);
    if(existing){if(existing.type!=='ORDER_REFUND'||existing.allocations[0].sourceId!==input.sourceId||existing.amountCents!==input.amountCents||existing.businessDate!==input.businessDate||existing.note!==input.note.trim()||JSON.stringify(existing.vouchers||[])!==JSON.stringify(vouchers))throw new Error('同一退款请求内容不一致');return clone(existing)}
    const source=qualifications().find(record=>record.id===input.sourceId&&record.type==='ORDER_REFUND');
    if(!source||!Number.isSafeInteger(input.amountCents)||input.amountCents<=0||input.amountCents>99999999||input.amountCents>source.limitCents-totals(source.orderId).refundedCents)throw new Error('退款超过订单当前有效退款义务');
    if(!validDate(input.businessDate)||input.businessDate<source.availableDate||input.businessDate>today())throw new Error('退款日期无效');
    if(input.expectedFingerprint&&input.expectedFingerprint!==receiptFingerprint(source.orderId))throw new Error('订单收退款情况已变化，请重新核对');
    const id=nextFactId();const fact={id,no:id,orderId:source.orderId,type:'ORDER_REFUND',direction:'OUT',businessDate:input.businessDate,recordedAt:recordedAt(),amountCents:input.amountCents,valid:true,note:input.note.trim(),...(input.requestId?{requestId:input.requestId}:{}),vouchers,allocations:[{sourceId:source.id,amountCents:input.amountCents}]};
    return commitFact(fact);
  }
  function reversalReason(fact){const original=state.facts.find(row=>row.id===fact?.id);if(!original||!original.valid||original.reversesId)return'该订单流水不能冲销';if(state.facts.some(row=>row.reversesId===original.id))return'该流水已冲销';if(original.type==='ORDER_RECEIPT'&&totals(original.orderId).netReceivedCents<original.amountCents)return'该收款已用于退款，请先处理关联退款';return''}
  function reverse(id,note){refresh();const original=state.facts.find(fact=>fact.id===id);const error=reversalReason(original);if(error)throw new Error(error);if(typeof note!=='string'||!note.trim()||note.length>200)throw new Error('请填写冲销原因，最多 200 个字符');const no=nextFactId();const fact={...clone(original),id:no,no,direction:original.direction==='IN'?'OUT':'IN',businessDate:today(),recordedAt:recordedAt(),note:note.trim(),reversesId:original.id};delete fact.requestId;delete fact.vouchers;return commitFact(fact)}
  function paymentsFor(no){return state.facts.filter(fact=>fact.orderId===no).map(fact=>({no:fact.no,type:fact.type==='ORDER_RECEIPT'?'RECEIPT':'REFUND',amountCents:fact.amountCents*(fact.reversesId?-1:1),date:fact.businessDate,valid:fact.valid,note:fact.note,vouchers:window.YumiPaymentVouchers.copy(fact.vouchers),reversesId:fact.reversesId}))}
  function qualifications(){return metadata().flatMap(order=>[{id:order.no,orderId:order.no,type:'ORDER_RECEIPT',label:order.no+' · '+order.customerName,limitCents:order.status==='CANCELLED'?0:order.receivableCents,availableDate:order.orderDate,reversalAllowed:true},{id:'RF-'+order.no.slice(3),orderId:order.no,type:'ORDER_REFUND',label:order.no+' · '+order.customerName+' · 订单退款义务',limitCents:Math.max(0,totals(order.no).receivedCents-order.receivableCents),availableDate:order.orderDate,reversalAllowed:true}])}
  function addOrder(order){refresh();if(orderMetadata(order.no))throw new Error('订单编号已存在，请重新确认');const candidate={...state,customOrders:[...state.customOrders,clone(order)]};if(!decode(JSON.stringify(candidate)))throw new Error('订单示例数据无效');state.customOrders.push(clone(order));persist()}
  function navigationUrl(path,options={}){refresh();const params=new URLSearchParams(options);params.set('orderState',JSON.stringify(state));return path+'#'+params.toString()}
  window.addEventListener('storage',event=>{if(event.key===storageKey)refresh()});
  window.addEventListener('focus',refresh);
  return{shippingView,shippingFingerprint,validateShipment,appendShipment,seedConfigs:clone(seedConfigs),facts,customOrders:()=>clone(state.customOrders),refresh,subscribe:callback=>subscribers.push(callback),remaining,totals,receiptFingerprint,appendReceipt,appendRefund,addOrder,paymentsFor,qualifications,reversalReason,reverse,navigationUrl,navigation};
})();
