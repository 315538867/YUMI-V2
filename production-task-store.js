(function(global){
  'use strict';
  const copy=value=>JSON.parse(JSON.stringify(value));
  const taskTypes={NORMAL_PRODUCTION:['正常生产','success'],EXTRA_PRODUCTION:['制作超额','warning'],REPAIR:['修补 / 重做','danger'],OTHER_TASK:['其他任务','info']};
  function validDate(value){
    if(typeof value!=='string'||!/^20\d{2}-\d{2}-\d{2}$/.test(value))return false;
    const parsed=new Date(value+'T00:00:00Z');
    return Number.isFinite(parsed.getTime())&&parsed.toISOString().slice(0,10)===value;
  }
  function create(options){
    const key='yumi-v2-production-tasks-v1';
    const sources=copy(options.sources),repairSources=copy(options.repairSources),seedTasks=copy(options.seedTasks);
    const executors=options.executors.slice();
    let saved=[],loadError='';
    function remaining(source,records){return source.available-records.reduce((total,task)=>total+task.items.filter(item=>item.source===source.id).reduce((subtotal,item)=>subtotal+item.quantity,0),0)}
    function canonical(input){
      const type=input?.type;
      return {type,executor:input?.executor,date:input?.date,process:type==='OTHER_TASK'?'其他任务':type==='REPAIR'?input?.repairTargetProcess:input?.process,normalTaskId:type==='EXTRA_PRODUCTION'?input?.normalTaskId:'',otherDescription:type==='OTHER_TASK'&&typeof input?.otherDescription==='string'?input.otherDescription.trim():'',items:type==='OTHER_TASK'?[]:Array.isArray(input?.items)?input.items.map(item=>({source:item?.source,quantity:item?.quantity})):null};
    }
    function fromRecord(task){return {type:task.typeCode,executor:task.executor,date:task.date,process:task.process,repairTargetProcess:task.process,normalTaskId:task.normalTaskId,otherDescription:task.otherDescription,items:task.items}}
    function build(input,records,metadata){
      const draft=canonical(input);
      if(!Object.hasOwn(taskTypes,draft.type))throw Error('请选择有效任务类型');
      if(!executors.includes(draft.executor))throw Error('请选择有效的执行员工或小组');
      if(!validDate(draft.date))throw Error('请选择有效的计划日期');
      if(typeof metadata.requestId!=='string'||!metadata.requestId||metadata.requestId.length>120)throw Error('任务提交标识无效，请重新打开新建任务');
      const sameDay=[...seedTasks,...records].filter(task=>task.executor===draft.executor&&task.date===draft.date&&task.status!=='已取消');
      if(draft.type==='OTHER_TASK'&&sameDay.length)throw Error('该执行主体当天已有任务，其他任务不能与生产任务共存且每天最多一个');
      if(draft.type!=='OTHER_TASK'&&sameDay.some(task=>task.typeCode==='OTHER_TASK'))throw Error('该执行主体当天已有其他任务，不能再安排生产任务');
      if(['NORMAL_PRODUCTION','EXTRA_PRODUCTION'].includes(draft.type)&&sameDay.some(task=>task.typeCode===draft.type))throw Error('该执行主体当天最多一个'+taskTypes[draft.type][0]+'任务');
      if(draft.type==='REPAIR'&&!sameDay.some(task=>task.typeCode==='NORMAL_PRODUCTION'&&task.status!=='已完成'))throw Error('修补 / 重做前必须有同执行主体同日有效的正常生产任务');
      const items=[];
      if(draft.type==='OTHER_TASK'){
        if(!draft.otherDescription||draft.otherDescription.length>500)throw Error('请填写其他任务内容，最多 500 字');
      }else{
        if(!Array.isArray(draft.items)||!draft.items.length||draft.items.length>20)throw Error('请选择 1 至 20 条生产来源明细');
        if(draft.type==='REPAIR'&&draft.items.length!==1)throw Error('修补 / 重做只允许一条不合格来源明细');
        if(new Set(draft.items.map(item=>item.source)).size!==draft.items.length)throw Error('同一来源不能重复安排');
        for(const item of draft.items){
          const source=(draft.type==='REPAIR'?repairSources:sources).find(source=>source.id===item.source);
          if(!source)throw Error('每条任务明细必须选择有效的生产来源');
          if(!Number.isSafeInteger(item.quantity)||item.quantity<1||item.quantity>999999)throw Error('本次安排数量必须是正整数');
          if(item.quantity>remaining(source,records))throw Error(source.id+' 可安排数量不足，请刷新来源后重新填写');
          if(draft.type==='REPAIR'){
            if(!source.history.includes(draft.process))throw Error('返工只能返回该制品已经历的工序');
          }else if(draft.process!==source.process)throw Error('同一个任务只能安排同一工序，工序由来源带出');
          items.push({source:source.id,product:source.product,productNo:source.productNo,order:source.order,snapshot:source.snapshot,process:draft.process,label:source.label,quantity:item.quantity});
        }
      }
      if(draft.type==='EXTRA_PRODUCTION'){
        if(draft.process!=='制作')throw Error('制作超额只允许制作工序');
        const normal=[...seedTasks,...records].find(task=>task.id===draft.normalTaskId);
        if(!normal||normal.typeCode!=='NORMAL_PRODUCTION'||normal.process!=='制作'||normal.executor!==draft.executor||normal.date!==draft.date||['已完成','已取消'].includes(normal.status))throw Error('制作超额必须关联同执行主体同日有效的制作正常任务');
      }
      const products=[...new Set(items.map(item=>item.product))],orders=[...new Set(items.map(item=>item.order))];
      return {id:metadata.id,requestId:metadata.requestId,createdAt:metadata.createdAt,typeCode:draft.type,type:taskTypes[draft.type][0],typeTag:taskTypes[draft.type][1],executor:draft.executor,executorShort:draft.executor.slice(0,1),date:draft.date,plan:draft.date,process:draft.process,normalTaskId:draft.normalTaskId,otherDescription:draft.otherDescription,items,quantity:items.reduce((total,item)=>total+item.quantity,0),product:draft.type==='OTHER_TASK'?draft.otherDescription:products.join('、'),productNo:items.map(item=>item.productNo).join('、')||'—',order:orders.join('、')||'内部安排',snapshot:[...new Set(items.map(item=>item.snapshot))].join('、')||'—',source:draft.type==='OTHER_TASK'?'管理员直接录入':items.map(item=>item.source).join('、'),duration:'待评估',status:'待开始',statusTag:'info'};
    }
    function decode(text){
      if(text===null)return [];
      if(text.length>2000000)throw Error('本地任务数据过大');
      const state=JSON.parse(text);
      if(state.version!==1||!Array.isArray(state.tasks)||state.tasks.length>500)throw Error('本地任务数据格式无效');
      const records=[],identifiers=new Set(seedTasks.map(task=>task.id)),requests=new Set();
      for(const task of state.tasks){
        if(!task||typeof task.id!=='string'||!/^T-20\d{6}-\d{3,}$/.test(task.id)||identifiers.has(task.id)||requests.has(task.requestId)||typeof task.createdAt!=='string'||!Number.isFinite(Date.parse(task.createdAt))||task.id.split('-')[1]!==task.date?.replaceAll('-',''))throw Error('本地任务编号或创建信息无效');
        const expected=build(fromRecord(task),records,task);
        if(JSON.stringify(expected)!==JSON.stringify(task))throw Error('本地任务明细或快照无效');
        records.push(expected);identifiers.add(task.id);requests.add(task.requestId);
      }
      return records;
    }
    function refresh(){
      try{saved=decode(global.localStorage.getItem(key));loadError='';return true}
      catch(error){loadError='无法读取本地任务记录，未覆盖原数据：'+error.message;return false}
    }
    function tasks(){refresh();return copy([...seedTasks,...saved])}
    function available(pool){refresh();return pool.map(source=>({...copy(source),available:remaining(source,saved)}))}
    function save(input,requestId){
      if(!refresh())throw Error(loadError);
      const existing=saved.find(task=>task.requestId===requestId);
      if(existing){
        if(JSON.stringify(canonical(input))!==JSON.stringify(canonical(fromRecord(existing))))throw Error('同一次提交内容已变化，请重新新建任务');
        return copy(existing);
      }
      if(saved.length>=500)throw Error('本地原型最多保存 500 个新增任务');
      const prefix='T-'+String(input?.date||'').replaceAll('-','')+'-';
      let serial=1;
      while([...seedTasks,...saved].some(task=>task.id===prefix+String(serial).padStart(3,'0')))serial++;
      const record=build(input,saved,{id:prefix+String(serial).padStart(3,'0'),requestId,createdAt:new Date().toISOString()});
      const next=[...saved,record],text=JSON.stringify({version:1,tasks:next});
      decode(text);
      try{global.localStorage.setItem(key,text)}catch(error){throw Error('保存失败，未新增任务或占用数量；请检查浏览器本地存储后重试')}
      saved=next;
      return copy(record);
    }
    refresh();
    return {key,tasks,sources:()=>available(sources),repairSources:()=>available(repairSources),save,error:()=>{refresh();return loadError}};
  }
  global.YumiProductionTasks={create};
})(window);
