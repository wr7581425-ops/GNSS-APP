// ================= Camada nativa (app instalado) =================
// No navegador continua tudo simulado; no app Android usa o receptor de verdade.
const NATIVO=!!(window.Capacitor&&window.Capacitor.isNativePlatform&&window.Capacitor.isNativePlatform());
const NP=NATIVO?window.Capacitor.Plugins:{};
const R={buf:"",ultimo:0,ultGGA:"",ggaEnv:0,temGST:false,ntripBytes:0,ntripSt:"",ntripMsg:"",srvIps:[],clientes:[],geoId:null,mounts:[]};
if(!NATIVO&&S.conexao==="celular")S.conexao="wifi";

function vib(ms){
  if(!S.cfg.vibrar&&ms!=="loc")return;
  try{
    if(NATIVO&&NP.Haptics){const lista=Array.isArray(ms)?ms:[ms];let t=0;lista.forEach((d,i)=>{if(i%2===0)setTimeout(()=>NP.Haptics.vibrate({duration:d}),t);t+=d})}
    else if(navigator.vibrate)navigator.vibrate(ms);
  }catch(e){}
}

// ---------- NMEA ----------
function dm2deg(v,h){if(!v)return NaN;const p=v.indexOf(".");const g=+v.slice(0,p-2),m=+v.slice(p-2);let r=g+m/60;if(h==="S"||h==="W")r=-r;return r}
function onDados(txt){
  R.buf+=txt;let i;
  while((i=R.buf.indexOf("\n"))>=0){const l=R.buf.slice(0,i).trim();R.buf=R.buf.slice(i+1);const k=l.indexOf("$");if(k>=0)nmea(l.slice(k))}
  if(R.buf.length>16000)R.buf="";
}
const TALKER={GP:"GPS",GL:"GLONASS",GA:"Galileo",GB:"BeiDou",BD:"BeiDou",GQ:"QZSS"};
function nmea(l){
  const star=l.indexOf("*"),body=l.slice(1,star>0?star:undefined);
  if(star>0){let c=0;for(const ch of body)c^=ch.charCodeAt(0);if(c!==parseInt(l.slice(star+1,star+3),16))return}
  const f=body.split(","),tal=f[0].slice(0,2),t=f[0].slice(2);
  R.ultimo=Date.now();G.nmea.push(l);if(G.nmea.length>60)G.nmea.splice(0,G.nmea.length-40);
  if(t==="GGA"){
    const q=+f[6];G.fix=q===4?4:q===5?5:q>0?1:0;G.sats=+f[7]||0;const hdop=+f[8];
    if(q>0&&f[2]){G.lat=dm2deg(f[2],f[3]);G.lon=dm2deg(f[4],f[5]);G.h=(+f[9]||0)+(+f[11]||0);atualizaEN()}
    G.idade=f[13]?+f[13]:0;R.ultGGA=l;
    if(!R.temGST&&!isNaN(hdop)){G.hrms=G.fix===4?0.01*Math.max(1,hdop):G.fix===5?0.25*hdop:1.5*hdop;G.vrms=G.hrms*1.7}
    if(!R.temGSA&&!isNaN(hdop))G.pdop=+(hdop*1.4).toFixed(1);
  }
  if(t==="GST"){const sl=+f[6],so=+f[7],sa=+f[8];if(!isNaN(sl)&&!isNaN(so)&&f[6]!==""){G.hrms=Math.sqrt(sl*sl+so*so);G.vrms=isNaN(sa)?G.hrms*1.7:sa;R.temGST=true}}
  if(t==="RMC"){const sp=(+f[7]||0)*0.514444,cr=+f[8];G.vel=sp;if(!isNaN(cr)&&f[8]!==""&&sp>0.2)G.course=cr}
  if(t==="VTG"){const cr=+f[1],kmh=+f[7];if(!isNaN(kmh))G.vel=kmh/3.6;if(!isNaN(cr)&&f[1]!==""&&G.vel>0.2)G.course=cr}
  if(t==="GSA"){const p=+f[15];if(!isNaN(p)&&p>0){G.pdop=p;R.temGSA=true}}
  if(t==="GSV"&&TALKER[tal]){G.const[TALKER[tal]]=+f[3]||0}
}

// ---------- conexão ----------
async function conectarReal(){
  if(S.conexao==="celular")return ligarGpsCelular();
  if(S.conexao==="ble"||S.conexao==="bt"){toast("Bluetooth entra na próxima versão. Use o Wi-Fi do receptor.");return}
  S.conectando=true;render();
  try{
    R.buf="";R.temGST=false;R.temGSA=false;G.nmea=[];G.const={GPS:0,GLONASS:0,Galileo:0,BeiDou:0};
    await NP.GnssTcp.connect({host:S.ip.trim(),port:parseInt(S.porta)||9902,wifi:S.conexao==="wifi"});
    S.conectando=false;S.conectado=true;S.fonte="tcp";S.desde=Date.now();R.ultimo=Date.now();render();bip("ok");toast("Receptor conectado. Aguardando dados NMEA…");
    setTimeout(()=>{if(S.conectado&&S.fonte==="tcp"&&!R.ultGGA)toast("Conectado, mas sem NMEA. Confira a porta e se a saída NMEA (GGA) está ligada no receptor.")},6000);
  }catch(e){S.conectando=false;render();bip("erro");toast("Não conectou: "+(e.message||e)+". Confira o Wi-Fi do receptor, o IP e a porta.")}
}
async function ligarGpsCelular(){
  try{
    const p=await NP.Geolocation.requestPermissions();
    if(p.location!=="granted"&&p.coarseLocation!=="granted"){toast("Permita a localização para usar o GPS do celular");return}
    R.geoId=await NP.Geolocation.watchPosition({enableHighAccuracy:true,timeout:15000,maximumAge:0},(pos,err)=>{
      if(!pos)return;const c=pos.coords;
      G.lat=c.latitude;G.lon=c.longitude;G.h=c.altitude||0;G.hrms=c.accuracy||9;G.vrms=c.altitudeAccuracy||G.hrms*1.5;
      G.fix=1;G.sats=0;G.vel=c.speed||0;if(c.heading!=null&&!isNaN(c.heading))G.course=c.heading;atualizaEN();R.ultimo=Date.now();
    });
    S.conectado=true;S.fonte="celular";S.desde=Date.now();render();toast("Usando o GPS do celular (precisão de metros)");
  }catch(e){toast("GPS do celular indisponível: "+(e.message||e))}
}
async function desconectarReal(){
  try{if(S.fonte==="tcp")await NP.GnssTcp.disconnect();if(S.fonte==="celular"&&R.geoId)await NP.Geolocation.clearWatch({id:R.geoId})}catch(e){}
  try{if(S.corrLigada)await NP.GnssTcp.ntripDisconnect()}catch(e){}
}

// ---------- NTRIP ----------
async function ntripLigar(){
  if(S.fonte!=="tcp"){toast("O NTRIP precisa do receptor conectado");return false}
  if(!S.caster||!S.mount){toast("Preencha o caster e o ponto de montagem");return false}
  await NP.GnssTcp.ntripConnect({host:S.caster.trim(),port:parseInt(S.cport)||2101,mount:S.mount.trim(),user:S.user||"",pass:S.senha||""});
  R.ggaEnv=0;return true;
}
async function buscarMounts(){
  if(!NATIVO){toast("A busca de pontos de montagem funciona no app instalado");return}
  toast("Buscando pontos de montagem…");
  try{
    const r=await NP.GnssTcp.ntripSourcetable({host:S.caster.trim(),port:parseInt(S.cport)||2101,user:S.user||"",pass:S.senha||""});
    const ms=r.text.split(/\r?\n/).filter(l=>l.startsWith("STR;")).map(l=>{const c=l.split(";");const lat=+c[9],lon=+c[10];
      const d=(S.conectado&&!isNaN(lat))?Math.hypot((lat-G.lat)*111.32,(lon-G.lon)*111.32*Math.cos(G.lat*Math.PI/180)):NaN;return {m:c[1],cid:c[2],fmt:c[3],d}});
    ms.sort((a,b)=>(isNaN(a.d)?1e9:a.d)-(isNaN(b.d)?1e9:b.d));R.mounts=ms.slice(0,40);render();
    toast(ms.length?`${ms.length} pontos de montagem encontrados`:"Nenhum ponto de montagem na lista");
  }catch(e){toast("Não consegui ler a lista: "+(e.message||e))}
}

// ---------- servidor NMEA ----------
async function servidorReal(ligar){
  try{
    if(ligar){const r=await NP.GnssTcp.startServer({port:parseInt(S.sport)||5017});R.srvIps=r.ips||[];S.servidor=true;toast("Servidor ligado na porta "+S.sport)}
    else{await NP.GnssTcp.stopServer();S.servidor=false;R.clientes=[];toast("Servidor desligado")}
  }catch(e){toast("Servidor: "+(e.message||e))}
  render();
}

// ---------- exportar e compartilhar ----------
async function compartilharArquivo(){
  if(!exportTxt){toast("Gere o arquivo primeiro");return}
  const ext=exportTxt.startsWith("<?xml")?"kml":exportTxt.includes("SECTION")?"dxf":"csv";
  const nome=(proj()?.nome||"projeto").replace(/[^\w\-]+/g,"_")+"."+ext;
  try{
    const w=await NP.Filesystem.writeFile({path:nome,data:exportTxt,directory:"CACHE",encoding:"utf8"});
    await NP.Share.share({title:nome,text:nome,files:[w.uri],dialogTitle:"Enviar "+nome});
  }catch(e){if(!String(e.message||e).includes("cancel"))toast("Não consegui compartilhar: "+(e.message||e))}
}

// ---------- ouvintes ----------
if(NATIVO&&NP.GnssTcp){
  NP.GnssTcp.addListener("data",d=>onDados(d.text));
  NP.GnssTcp.addListener("status",s=>{
    if(s.kind==="desconectado"&&S.fonte==="tcp"&&S.conectado){S.conectado=false;S.corrLigada=false;bip("erro");toast("Receptor desconectado: "+(s.msg||""));render()}
  });
  NP.GnssTcp.addListener("ntrip",n=>{
    R.ntripBytes=n.bytes;const ant=R.ntripSt;R.ntripSt=n.status;R.ntripMsg=n.msg;
    if(n.status==="erro"){S.corrLigada=false;bip("erro");toast("NTRIP: "+n.msg);render()}
    else if(n.status==="recebendo"&&ant!=="recebendo")toast("NTRIP recebendo correção de "+n.msg);
  });
  NP.GnssTcp.addListener("clients",c=>{R.clientes=c.list||[];render(false)});
}
// envia a posição (GGA) ao caster a cada 10 s, exigido em redes VRS
setInterval(()=>{if(NATIVO&&S.corrLigada&&S.correcao==="ntrip"&&S.enviaGGA&&R.ultGGA&&Date.now()-R.ggaEnv>10000){R.ggaEnv=Date.now();NP.GnssTcp.ntripGGA({line:R.ultGGA}).catch(()=>{})}},2000);
