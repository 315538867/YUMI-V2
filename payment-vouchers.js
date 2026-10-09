window.YumiPaymentVouchers=(()=>{
  const maxFiles=3;
  const maxFileSize=300*1024;
  const maxStoreSize=1300*1024;
  const types=['image/jpeg','image/png','image/webp','application/pdf'];
  const extensions={'image/jpeg':['jpg','jpeg'],'image/png':['png'],'image/webp':['webp'],'application/pdf':['pdf']};
  function validate(vouchers){
    if(!Array.isArray(vouchers)||vouchers.length>maxFiles)return'每条记录最多附 3 份凭证';
    const seen=new Set();
    for(const voucher of vouchers){
      if(!voucher||typeof voucher.id!=='string'||!/^PV-[A-Za-z0-9-]{1,80}$/.test(voucher.id)||seen.has(voucher.id))return'凭证标识无效或重复';
      seen.add(voucher.id);
      if(typeof voucher.name!=='string'||!voucher.name.trim()||voucher.name.length>180||/[\x00-\x1f\x7f]/.test(voucher.name))return'凭证文件名无效';
      if(!types.includes(voucher.type)||!extensions[voucher.type].includes(voucher.name.split('.').at(-1).toLowerCase()))return'仅支持 JPG、PNG、WebP 或 PDF 凭证';
      if(!Number.isSafeInteger(voucher.size)||voucher.size<=0||voucher.size>maxFileSize)return'本地原型每份凭证不得超过 300KB';
      const prefix='data:'+voucher.type+';base64,';
      if(typeof voucher.dataUrl!=='string'||!voucher.dataUrl.startsWith(prefix)||voucher.dataUrl.length>maxFileSize*4/3+100)return'凭证内容无效';
      const payload=voucher.dataUrl.slice(prefix.length);
      if(!payload||payload.length%4!==0||!/^[A-Za-z0-9+/]+={0,2}$/.test(payload)||payload.length*3/4-(payload.endsWith('==')?2:payload.endsWith('=')?1:0)!==voucher.size)return'凭证内容与文件大小不一致';
      let header;
      try{header=atob(payload.slice(0,48))}catch{return'凭证内容无效'}
      if(voucher.type==='image/jpeg'&&!header.startsWith('\xff\xd8\xff')||voucher.type==='image/png'&&!header.startsWith('\x89PNG\r\n\x1a\n')||voucher.type==='image/webp'&&!(header.startsWith('RIFF')&&header.slice(8,12)==='WEBP')||voucher.type==='application/pdf'&&!header.startsWith('%PDF-'))return'文件内容与凭证格式不一致';
    }
    return'';
  }
  function copy(vouchers=[]){const error=validate(vouchers);if(error)throw new Error(error);return vouchers.map(({id,name,type,size,dataUrl})=>({id,name,type,size,dataUrl}))}
  function capacity(records){
    let size=0;
    const seen=new Map();
    for(const record of records){
      const vouchers=record.vouchers===undefined?[]:record.vouchers;
      const error=validate(vouchers);if(error)return error;
      for(const voucher of vouchers){
        if(seen.has(voucher.id)){if(seen.get(voucher.id)!==JSON.stringify(voucher))return'同一凭证标识的内容不一致';continue}
        seen.set(voucher.id,JSON.stringify(voucher));size+=voucher.dataUrl.length;
      }
    }
    return size>maxStoreSize?'本地原型凭证容量已满，请减少或压缩凭证后重试':'';
  }
  async function readFiles(files,existing=[]){
    const result=copy(existing);
    if(result.length+files.length>maxFiles)throw new Error('每条记录最多附 3 份凭证');
    for(const file of files){
      const type=file.type||types.find(value=>extensions[value].includes(file.name.split('.').at(-1).toLowerCase()));
      if(!types.includes(type))throw new Error('仅支持 JPG、PNG、WebP 或 PDF 凭证');
      if(!Number.isSafeInteger(file.size)||file.size<=0||file.size>maxFileSize)throw new Error('本地原型每份凭证不得超过 300KB');
      const dataUrl=await new Promise((resolve,reject)=>{
        const reader=new FileReader();
        reader.onload=()=>resolve(reader.result);
        reader.onerror=()=>reject(new Error('凭证读取失败，请重新选择文件'));
        reader.onabort=()=>reject(new Error('凭证读取已取消'));
        reader.readAsDataURL(file);
      });
      if(typeof dataUrl!=='string'||!/^data:[^,]*;base64,/.test(dataUrl))throw new Error('凭证读取失败');
      result.push({id:'PV-'+Date.now()+'-'+Math.random().toString(36).slice(2),name:file.name,type,size:file.size,dataUrl:'data:'+type+';base64,'+dataUrl.slice(dataUrl.indexOf(',')+1)});
    }
    const error=validate(result);if(error)throw new Error(error);
    return result;
  }
  const component={
    props:{modelValue:{type:Array,default:()=>[]},readonly:Boolean,disabled:Boolean},
    emits:['update:modelValue','busy-change'],
    setup(props,{emit}){
      const fileInput=Vue.ref(null);
      const loading=Vue.ref(false);
      const previewVisible=Vue.ref(false);
      const selected=Vue.ref(null);
      let mounted=true;
      Vue.onUnmounted(()=>{mounted=false;if(loading.value)emit('busy-change',false)});
      function show(voucher){const error=validate([voucher]);if(error){ElementPlus.ElMessage.error(error);return}selected.value=copy([voucher])[0];previewVisible.value=true}
      function remove(id){if(props.readonly||props.disabled||loading.value)return;emit('update:modelValue',copy(props.modelValue.filter(voucher=>voucher.id!==id)))}
      async function selectFiles(event){
        const input=event.target;
        const files=Array.from(input.files||[]);
        if(!files.length||props.readonly||props.disabled||loading.value){input.value='';return}
        const original=props.modelValue;
        loading.value=true;emit('busy-change',true);
        try{
          const vouchers=await readFiles(files,original);
          if(mounted&&!props.disabled&&!props.readonly&&props.modelValue===original)emit('update:modelValue',vouchers);
        }catch(error){if(mounted)ElementPlus.ElMessage.error(error.message)}
        finally{input.value='';loading.value=false;if(mounted)emit('busy-change',false)}
      }
      function sizeLabel(size){return(size/1024).toFixed(1)+' KB'}
      return{fileInput,loading,previewVisible,selected,show,remove,selectFiles,sizeLabel};
    },
    template:`<div class="payment-vouchers">
      <div v-if="!readonly" class="voucher-toolbar"><input ref="fileInput" class="voucher-file-input" type="file" multiple accept="image/jpeg,image/png,image/webp,application/pdf" :disabled="disabled||loading" @change="selectFiles"><el-button :loading="loading" :disabled="disabled||loading||modelValue.length>=3" @click="fileInput&&fileInput.click()">选择凭证</el-button><span class="voucher-count">{{modelValue.length}} / 3 份</span></div>
      <small v-if="!readonly" class="voucher-hint">支持 JPG、PNG、WebP、PDF，每份不超过 300KB。仅用于本地原型演示，不上传服务器，请勿使用真实敏感凭证。</small>
      <ul v-if="modelValue.length" class="voucher-list"><li v-for="voucher in modelValue" :key="voucher.id"><button class="voucher-open" type="button" @click="show(voucher)"><img v-if="voucher.type!=='application/pdf'" :src="voucher.dataUrl" alt="凭证缩略图"><span v-else class="voucher-pdf">PDF</span><span class="voucher-file-name">{{voucher.name}}<small>{{sizeLabel(voucher.size)}}</small></span></button><el-button v-if="!readonly" link type="danger" :disabled="disabled||loading" @click="remove(voucher.id)">移除</el-button></li></ul>
      <span v-else-if="readonly" class="voucher-empty">未附凭证</span>
      <el-dialog v-model="previewVisible" :title="selected?selected.name:'查看凭证'" width="640px" append-to-body><template v-if="selected"><img v-if="selected.type!=='application/pdf'" class="voucher-preview" :src="selected.dataUrl" :alt="selected.name"><p v-else class="voucher-hint">PDF 凭证请下载后查看。</p><a class="voucher-download" :href="selected.dataUrl" :download="selected.name">下载凭证</a></template></el-dialog>
    </div>`
  };
  return{validate,copy,capacity,readFiles,component};
})();
