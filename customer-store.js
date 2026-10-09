window.YumiCustomerStore=(()=>{
  const storageKey='yumi-v2-customers-v1';
  const seedCustomers=[
    {id:1,no:'C-0001',name:'花间礼物',contact:'陈女士',phone:'13800001001',address:'浙江省杭州市西湖区文三路 88 号花间礼物店',status:'ACTIVE',version:3},
    {id:2,no:'C-0002',name:'山岚手作',contact:'陆先生',phone:'13900002002',address:'江苏省苏州市姑苏区平江路 126 号山岚手作',status:'ACTIVE',version:1},
    {id:3,no:'C-0003',name:'拾光集合店',contact:'赵女士',phone:'13700003003',address:'上海市徐汇区衡山路 68 号拾光集合店',status:'ACTIVE',version:2},
    {id:4,no:'C-0004',name:'青木工作室',contact:'杨先生',phone:'13600004004',address:'浙江省宁波市鄞州区中山东路 118 号',status:'ACTIVE',version:1},
    {id:5,no:'C-0005',name:'旧时小店',contact:'唐女士',phone:'13500005005',address:'浙江省杭州市拱墅区湖墅南路 56 号',status:'INACTIVE',version:2}
  ];
  const clone=value=>JSON.parse(JSON.stringify(value));
  const subscribers=[];
  const navigation=new URLSearchParams((window.location.hash||'').slice(1));
  let state={version:1,customers:clone(seedCustomers)};
  function normalize(input){return Object.fromEntries(['name','contact','phone','address'].map(field=>[field,typeof input?.[field]==='string'?input[field].trim():'']))}
  function validate(input){
    const fields=normalize(input);
    if(!fields.name||fields.name.length>80||/[\x00-\x1f\x7f]/.test(fields.name))return'请填写客户名称，最多 80 个字符，不能包含换行';
    if(!fields.contact||fields.contact.length>40||/[\x00-\x1f\x7f]/.test(fields.contact))return'请填写联系人，最多 40 个字符，不能包含换行';
    if(!/^[\d+() -]{6,30}$/.test(fields.phone)||fields.phone.replace(/\D/g,'').length<6)return'请填写有效的联系电话';
    if(!fields.address||fields.address.length>200||/[\x00-\x09\x0b\x0c\x0e-\x1f\x7f]/.test(fields.address))return'请填写默认收货地址，最多 200 个字符';
    return'';
  }
  function decode(text){
    try{
      if(typeof text!=='string'||text.length>1024*1024)return null;
      const incoming=JSON.parse(text);
      if(incoming?.version!==1||!Array.isArray(incoming.customers)||incoming.customers.length<seedCustomers.length||incoming.customers.length>500)return null;
      if(!incoming.customers.every(record=>Number.isSafeInteger(record.id)&&record.id>0&&record.id<=999999&&record.no==='C-'+String(record.id).padStart(4,'0')&&['ACTIVE','INACTIVE'].includes(record.status)&&Number.isSafeInteger(record.version)&&record.version>0&&!validate(record)))return null;
      if(new Set(incoming.customers.map(record=>record.id)).size!==incoming.customers.length)return null;
      if(!seedCustomers.every(seed=>incoming.customers.some(record=>record.id===seed.id&&record.version>=seed.version)))return null;
      return{version:1,customers:incoming.customers.map(record=>({id:record.id,no:record.no,...normalize(record),status:record.status,version:record.version}))};
    }catch{return null}
  }
  function stored(){try{return decode(window.localStorage.getItem(storageKey))}catch{return null}}
  function merge(incoming){
    if(!incoming)return false;
    let changed=false;
    for(const record of incoming.customers){
      const index=state.customers.findIndex(current=>current.id===record.id);
      if(index<0){state.customers.push(clone(record));changed=true}
      else if(record.version>state.customers[index].version){state.customers[index]=clone(record);changed=true}
    }
    state.customers.sort((first,second)=>first.id-second.id);
    return changed;
  }
  function notify(){subscribers.forEach(callback=>callback())}
  function refresh(){if(merge(stored()))notify()}
  function commit(next){
    if(!decode(JSON.stringify(next)))throw new Error('客户原型数据超出支持范围');
    try{window.localStorage.setItem(storageKey,JSON.stringify(next))}catch{throw new Error('无法保存客户资料，请检查浏览器本地存储后重试；本次操作尚未保存')}
    state=clone(next);notify();
  }
  function current(id,expectedVersion){
    refresh();
    const record=state.customers.find(customer=>customer.id===id);
    if(!record)throw new Error('客户不存在，请刷新列表');
    if(record.version!==expectedVersion)throw new Error('客户资料已变化，请重新打开并核对后操作');
    return record;
  }
  function create(input){
    refresh();const error=validate(input);if(error)throw new Error(error);
    if(state.customers.length>=500)throw new Error('本地原型最多保存 500 位客户');
    const id=Math.max(...state.customers.map(record=>record.id))+1;
    const record={id,no:'C-'+String(id).padStart(4,'0'),...normalize(input),status:'ACTIVE',version:1};
    commit({version:1,customers:[...state.customers,record]});return clone(record);
  }
  function replace(record){commit({version:1,customers:state.customers.map(current=>current.id===record.id?record:current)});return clone(record)}
  function update(id,input,expectedVersion){
    const before=current(id,expectedVersion);const error=validate(input);if(error)throw new Error(error);
    const fields=normalize(input);
    if(Object.keys(fields).every(field=>fields[field]===before[field]))return clone(before);
    return replace({...before,...fields,version:before.version+1});
  }
  function setStatus(id,status,expectedVersion){
    const before=current(id,expectedVersion);
    if(!['ACTIVE','INACTIVE'].includes(status))throw new Error('请选择有效的客户状态');
    if(status===before.status)return clone(before);
    return replace({...before,status,version:before.version+1});
  }
  function snapshot(id,shipping,expectedVersion){
    const record=current(id,expectedVersion);
    if(record.status!=='ACTIVE')throw new Error('客户已停用，不能用于新订单');
    if(validate({name:record.name,contact:shipping?.recipient,phone:shipping?.phone,address:shipping?.address}))throw new Error('请完整填写本次订单的收货信息');
    const result={...clone(record),shipping:{recipient:shipping.recipient.trim(),phone:shipping.phone.trim(),address:shipping.address.trim()}};
    function freeze(value){if(value&&typeof value==='object'){Object.values(value).forEach(freeze);Object.freeze(value)}return value}
    return freeze(result);
  }
  function navigationUrl(path,options={}){
    refresh();const separator=path.indexOf('#');const base=separator>=0?path.slice(0,separator):path;
    const params=new URLSearchParams(separator>=0?path.slice(separator+1):'');
    Object.entries(options).forEach(([key,value])=>params.set(key,String(value)));
    params.set('customerState',JSON.stringify(state));return base+'#'+params.toString();
  }
  merge(decode(navigation.get('customerState')));merge(stored());
  if(navigation.has('customerState')){try{window.localStorage.setItem(storageKey,JSON.stringify(state))}catch{}}
  window.addEventListener('storage',event=>{if(event.key===storageKey||event.key===null)refresh()});
  window.addEventListener('focus',refresh);
  return{seedCustomers:clone(seedCustomers),list:()=>clone(state.customers),validate,refresh,subscribe:callback=>subscribers.push(callback),create,update,setStatus,snapshot,navigationUrl,navigation};
})();
