package com.aero.store;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

import io.github.muntashirakon.adb.AdbStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aero Store wrapper.
 *
 * All download / install UI (Get button, progress ring, Downloads page) now lives in the
 * web page so it follows the selected app theme (light / dark / green / glass).
 * This class only does the native work and exposes it to the page as "AeroBridge":
 *   start(url, fileName)  - download a direct link
 *   cancel(url)           - cancel or remove a download
 *   install(url)          - open the Android installer for a finished download
 *   mark(url)             - remember which app a MediaFire page link belongs to
 *   state()               - JSON of all downloads: {url:{status,pct,got,total}}
 * and pushes changes back with window.aeroOnState(json).
 */
public class MainActivity extends Activity {

    // The build reads your link from site.txt and puts it here.
    static final String SITE = "https://YOUR-SITE.pages.dev";

    /* Shown instead of the default Android error page when the site cannot load: the offline game (Aero Run). */
    static final String OFFLINE_BASE = "https://aero-run.local/";
    static final String OFFLINE_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
<title>Aero Run</title>
<style>
:root{
  --bg:#f5f6fa;--surface:#fff;--ink:#12141f;--mute:#6b7085;--line:#e3e5ee;
  --brand:#4b3df2;--on-brand:#fff;--accent:#f5b041;--danger:#e5586b;--ok:#1fa971;
  color-scheme:light;
}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#0d0f19;--surface:#161a2a;--ink:#eef0fa;--mute:#98a0ba;--line:#252a45;--brand:#7b6dff;--on-brand:#0d0f19;color-scheme:dark}}
:root[data-theme="dark"]{--bg:#0d0f19;--surface:#161a2a;--ink:#eef0fa;--mute:#98a0ba;--line:#252a45;--brand:#7b6dff;--on-brand:#0d0f19;color-scheme:dark}
:root[data-theme="green"]{--bg:#08150f;--surface:#0f2218;--ink:#e8f6ee;--mute:#8fb5a0;--line:#1c3b2b;--brand:#2fc77a;--on-brand:#04130b;--accent:#b5ed75;color-scheme:dark}
:root[data-theme="glass"]{--bg:#0a0b19;--surface:#15162a;--ink:#f3f4ff;--mute:#a9aecb;--line:#2a2c4a;--brand:#9a8cff;--on-brand:#0b0d17;--accent:#ff8fc2;color-scheme:dark}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;height:100%;overflow:hidden}
body{font-family:'Plus Jakarta Sans','Hind Siliguri',system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;
  background:var(--bg);color:var(--ink);line-height:1.45;touch-action:manipulation;-webkit-user-select:none;user-select:none}
.app{height:100%;max-width:520px;margin:0 auto;display:flex;flex-direction:column;
  padding:calc(16px + var(--sat,env(safe-area-inset-top,0px))) 20px calc(18px + env(safe-area-inset-bottom,0px))}
.top{display:flex;align-items:center;justify-content:space-between;gap:12px}
.brand{display:flex;align-items:center;gap:10px;min-width:0}
.logo{width:34px;height:34px;flex:none;border-radius:10px}
.name{font-size:16px;font-weight:700;letter-spacing:-.2px}
.chip{flex:none;display:flex;align-items:center;gap:7px;font-size:12px;font-weight:600;color:var(--mute)}
.chip i{width:8px;height:8px;border-radius:50%;background:var(--danger)}
.chip.on{color:var(--ok)}
.chip.on i{background:var(--ok)}
.msg{margin:22px 0 16px}
.msg h1{margin:0 0 6px;font-size:24px;font-weight:800;letter-spacing:-.5px}
.msg p{margin:0;font-size:14px;color:var(--mute)}
.card{flex:1;min-height:0;display:flex;flex-direction:column;background:var(--surface);border:1px solid var(--line);border-radius:20px;padding:14px}
.hud{display:flex;justify-content:space-between;align-items:baseline;margin:0 2px 10px}
.pill{display:flex;align-items:baseline;gap:8px}
.pill span{font-size:11px;font-weight:700;letter-spacing:.8px;color:var(--mute)}
.pill b{font-size:18px;font-weight:800;font-variant-numeric:tabular-nums}
.pill.best b{color:var(--brand)}
.stage{position:relative;flex:1;min-height:200px;border-radius:14px;overflow:hidden;border:1px solid var(--line);touch-action:none;cursor:pointer}
canvas{position:absolute;inset:0;width:100%;height:100%;display:block}
.pz{position:absolute;top:10px;right:10px;width:34px;height:34px;border-radius:50%;border:1px solid var(--line);background:var(--surface);color:var(--ink);display:grid;place-items:center;padding:0;cursor:pointer}
.pz svg{width:14px;height:14px;fill:currentColor}
.pz[hidden]{display:none}
.overlay{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;padding:18px;background:color-mix(in srgb,var(--bg) 60%,transparent);transition:opacity .2s}
.overlay.hidden{opacity:0;pointer-events:none}
.panel{width:100%;max-width:260px;text-align:center}
.panel h2{margin:0 0 4px;font-size:20px;font-weight:800;letter-spacing:-.3px}
.panel p{margin:0 0 16px;font-size:13px;color:var(--mute)}
.res{display:none;gap:8px;margin:0 0 14px}
.res.on{display:flex}
.res div{flex:1;border-radius:12px;padding:8px 6px;background:var(--surface);border:1px solid var(--line)}
.res small{display:block;font-size:10px;font-weight:700;letter-spacing:.8px;color:var(--mute)}
.res b{font-size:20px;font-weight:800;font-variant-numeric:tabular-nums}
.nb{display:none;margin:0 auto 10px;width:max-content;font-size:11px;font-weight:700;padding:4px 10px;border-radius:99px;background:var(--accent);color:#2a1c00}
.nb.on{display:block}
.btn{font:inherit;font-weight:700;font-size:15px;cursor:pointer;border:0;border-radius:12px;padding:12px 26px;background:var(--brand);color:var(--on-brand);transition:transform .1s}
.btn:active{transform:scale(.97)}
.panel .btn{width:100%}
.hint{font-size:12px;color:var(--mute);text-align:center;margin:12px 0}
.rc{font:inherit;font-weight:700;font-size:15px;cursor:pointer;width:100%;padding:14px 18px;border-radius:12px;background:var(--brand);color:var(--on-brand);border:0;transition:transform .1s,background .2s}
.rc:active{transform:scale(.98)}
.rc.on{background:var(--ok);color:#fff}
@media (max-height:620px){.msg p,.hint{display:none}.msg{margin:14px 0 10px}}
@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
</style>
</head>
<body>
<svg width="0" height="0" style="position:absolute" aria-hidden="true">
  <defs>
    <linearGradient id="lgA" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#2f8bff"/><stop offset="1" stop-color="#9b3cff"/></linearGradient>
    <linearGradient id="lgB" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#3b7bff"/><stop offset="1" stop-color="#8a3ffc"/></linearGradient>
    <symbol id="logo" viewBox="0 0 100 100">
      <rect width="100" height="100" rx="24" fill="url(#lgA)"/>
      <path d="M38 36v-7a12 12 0 0 1 24 0v7" fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round" opacity=".92"/>
      <rect x="22" y="34" width="56" height="48" rx="13" fill="#f3f5ff"/>
      <path d="M38 69l12-27 12 27" fill="none" stroke="url(#lgB)" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/>
      <path d="M43 62h14" stroke="#27c4d9" stroke-width="4.5" stroke-linecap="round"/>
    </symbol>
  </defs>
</svg>

<main class="app">
  <header class="top">
    <div class="brand">
      <svg class="logo" viewBox="0 0 100 100" aria-hidden="true"><use href="#logo"/></svg>
      <span class="name">Aero Store</span>
    </div>
    <div class="chip" id="chip"><i></i><span id="chipT">Offline</span></div>
  </header>

  <section class="msg">
    <h1>You're offline</h1>
    <p>Check your Wi-Fi or mobile data. Play a quick game while you wait.</p>
  </section>

  <section class="card">
    <div class="hud">
      <div class="pill"><span>SCORE</span><b id="score">0</b></div>
      <div class="pill best"><span>BEST</span><b id="best">0</b></div>
    </div>
    <div class="stage" id="stage">
      <canvas id="game" aria-label="Aero Run game"></canvas>
      <button class="pz" id="pause" type="button" aria-label="Pause" hidden>
        <svg viewBox="0 0 16 16"><rect x="3" y="2" width="3.4" height="12" rx="1.2"/><rect x="9.6" y="2" width="3.4" height="12" rx="1.2"/></svg>
      </button>
      <div class="overlay" id="overlay">
        <div class="panel">
          <div class="nb" id="nb">NEW BEST</div>
          <h2 id="headline">Ready to run?</h2>
          <p id="message">Jump over the parcels and collect the coins.</p>
          <div class="res" id="res"><div><small>SCORE</small><b id="rs">0</b></div><div><small>BEST</small><b id="rb">0</b></div></div>
          <button class="btn" id="start" type="button">Play</button>
        </div>
      </div>
    </div>
  </section>

  <p class="hint">Tap to jump &middot; hold to jump higher</p>
  <button class="rc" id="reconnect" type="button">Try again</button>
</main>

<script>
(function(){
  /* ---- theme: same as the store ---- */
  try{
    var saved=null;
    try{saved=window.AeroBridge&&AeroBridge.getTheme?AeroBridge.getTheme():localStorage.getItem('theme')}catch(e){}
    if(['dark','light','green','glass'].indexOf(saved)>-1)document.documentElement.dataset.theme=saved;
  }catch(e){}

  /* ---- reconnect ---- */
  var rc=document.getElementById('reconnect'),chip=document.getElementById('chip'),chipT=document.getElementById('chipT');
  rc.addEventListener('click',function(){try{AeroBridge.retry()}catch(e){location.reload()}});
  function online(){rc.classList.add('on');rc.textContent="Back online \\u00b7 Open Aero Store";chip.classList.add('on');chipT.textContent='Back online'}
  function offline(){rc.classList.remove('on');rc.textContent='Try again';chip.classList.remove('on');chipT.textContent='Offline'}
  addEventListener('online',online);addEventListener('offline',offline);

  /* ---- canvas helpers ---- */
  if(window.CanvasRenderingContext2D&&!CanvasRenderingContext2D.prototype.roundRect){
    CanvasRenderingContext2D.prototype.roundRect=function(x,y,w,h,r){
      r=Math.max(0,Math.min(r,w/2,h/2));
      this.moveTo(x+r,y);this.arcTo(x+w,y,x+w,y+h,r);this.arcTo(x+w,y+h,x,y+h,r);this.arcTo(x,y+h,x,y,r);this.arcTo(x,y,x+w,y,r);this.closePath();
    };
  }
  var canvas=document.getElementById('game'),ctx=canvas.getContext('2d'),stage=document.getElementById('stage');
  var scoreEl=document.getElementById('score'),bestEl=document.getElementById('best');
  var overlay=document.getElementById('overlay'),headline=document.getElementById('headline'),message=document.getElementById('message');
  var startBtn=document.getElementById('start'),pauseBtn=document.getElementById('pause');
  var res=document.getElementById('res'),rs=document.getElementById('rs'),rb=document.getElementById('rb'),nb=document.getElementById('nb');

  var css=function(n){return getComputedStyle(document.documentElement).getPropertyValue(n).trim()};
  function hex(c){c=c.replace('#','');if(c.length===3)c=c[0]+c[0]+c[1]+c[1]+c[2]+c[2];var n=parseInt(c,16);return[(n>>16)&255,(n>>8)&255,n&255]}
  function mix(c,t,a){try{var A=hex(c),B=hex(t);return'rgb('+Math.round(A[0]+(B[0]-A[0])*a)+','+Math.round(A[1]+(B[1]-A[1])*a)+','+Math.round(A[2]+(B[2]-A[2])*a)+')'}catch(e){return c}}
  var pal={};
  function readPal(){
    pal.brand=css('--brand')||'#4b3df2';pal.ink=css('--ink')||'#0f1222';pal.mute=css('--mute')||'#6a6f85';
    pal.accent=css('--accent')||'#ffb84d';pal.danger=css('--danger')||'#ef6478';pal.line=css('--line')||'#e4e6ef';
    var bg=css('--bg')||'#f3f4f8';pal.bg=/^#/.test(bg)?bg:'#f3f4f8';
  }

  var W=0,H=0,dpr=1,GH=40,ground=0,last=0,raf=0,t=0,mode='ready';
  var score=0,best=0,speed=5,dist=0,nextAt=0,obstacles=[],coins=[],puffs=[],clouds=[],shake=0,flash=0;
  var P={x:58,y:0,w:34,h:40,vy:0,ground:true,sq:0};
  var G=0.7,JUMP=-12.8,held=false;

  function resize(){
    var r=stage.getBoundingClientRect();if(!r.width||!r.height)return;
    dpr=Math.min(window.devicePixelRatio||1,2);
    var S=Math.max(1,Math.min(1.7,r.height/300));  /* taller screens get a bigger scene */
    W=r.width/S;H=r.height/S;
    canvas.width=Math.round(r.width*dpr);canvas.height=Math.round(r.height*dpr);ctx.setTransform(dpr*S,0,0,dpr*S,0,0);
    ground=H-GH;if(P.ground)P.y=ground-P.h;
    clouds=[{x:W*.15,y:H*.2,s:1},{x:W*.55,y:H*.12,s:.8},{x:W*.9,y:H*.28,s:.65}];
    readPal();draw();
  }
  function rr(x,y,w,h,r,f){ctx.beginPath();ctx.roundRect(x,y,w,h,r);ctx.fillStyle=f;ctx.fill()}

  /* ---- drawing ---- */
  function hills(off,base,amp,per,col,alpha){
    ctx.beginPath();ctx.moveTo(0,ground);
    for(var x=0;x<=W+6;x+=6){ctx.lineTo(x,base-amp*(0.5+0.5*Math.sin((x+off)/per))-amp*.35*Math.sin((x+off)/(per*.47)))}
    ctx.lineTo(W,ground);ctx.closePath();ctx.globalAlpha=alpha;ctx.fillStyle=col;ctx.fill();ctx.globalAlpha=1;
  }
  function cloud(c){
    ctx.save();ctx.globalAlpha=.4;ctx.fillStyle=pal.mute;ctx.beginPath();
    ctx.arc(c.x,c.y,10*c.s,0,6.283);ctx.arc(c.x+13*c.s,c.y-6*c.s,13*c.s,0,6.283);ctx.arc(c.x+28*c.s,c.y,9*c.s,0,6.283);ctx.fill();ctx.restore();
  }
  function drawBox(o){
    var base=mix(pal.danger,'#ffffff',.0),lite=mix(pal.danger,'#ffffff',.35);
    rr(o.x,o.y,o.w,o.h,6,base);
    ctx.fillStyle=lite;ctx.globalAlpha=.9;ctx.fillRect(o.x+o.w/2-3,o.y,6,o.h);ctx.globalAlpha=1;
    ctx.fillStyle='rgba(255,255,255,.22)';ctx.fillRect(o.x+4,o.y+4,4,Math.max(6,o.h-12));
    ctx.fillStyle='rgba(0,0,0,.12)';ctx.fillRect(o.x,o.y+o.h-5,o.w,5);
  }
  function drawCoin(c){
    var bob=Math.sin(t*.08+c.x*.03)*2.5,x=c.x,y=c.y+bob;
    ctx.globalAlpha=.2;ctx.fillStyle=pal.accent;ctx.beginPath();ctx.arc(x,y,15,0,6.283);ctx.fill();ctx.globalAlpha=1;
    ctx.fillStyle=pal.accent;ctx.beginPath();ctx.arc(x,y,10,0,6.283);ctx.fill();
    ctx.strokeStyle='rgba(0,0,0,.28)';ctx.lineWidth=2.4;ctx.lineCap='round';ctx.lineJoin='round';
    ctx.beginPath();ctx.moveTo(x,y-4.5);ctx.lineTo(x,y+3.5);ctx.moveTo(x-3.6,y+.2);ctx.lineTo(x,y+4);ctx.lineTo(x+3.6,y+.2);ctx.stroke();
  }
  function drawPlayer(){
    var cx=P.x+P.w/2,by=P.y+P.h;
    var rot=P.ground?0:Math.max(-.22,Math.min(.3,P.vy*.022));
    var sy=1-P.sq*.18+(P.ground?0:Math.min(.1,Math.abs(P.vy)*.006)),sx=2-sy>1.25?1.25:2-sy;
    ctx.save();ctx.translate(cx,by);ctx.rotate(rot);ctx.scale(sx,sy);ctx.translate(-cx,-by);
    var x=P.x,y=P.y,w=P.w,bh=P.h-8;
    /* feet */
    var ph=P.ground?Math.sin(t*.55)*4:0;
    rr(x+4,by-6+(ph>0?-Math.abs(ph)*.5:0),11,7,3.5,pal.ink);rr(x+w-15,by-6+(ph<0?-Math.abs(ph)*.5:0),11,7,3.5,pal.ink);
    /* handle */
    ctx.strokeStyle=mix(pal.brand,'#000000',.15);ctx.lineWidth=3.4;ctx.lineCap='round';
    ctx.beginPath();ctx.moveTo(x+10,y+8);ctx.bezierCurveTo(x+10,y-5,x+w-10,y-5,x+w-10,y+8);ctx.stroke();
    /* body */
    var g=ctx.createLinearGradient(x,y,x+w,y+bh);g.addColorStop(0,mix(pal.brand,'#ffffff',.25));g.addColorStop(1,pal.brand);
    rr(x,y+7,w,bh,10,g);
    /* eyes */
    rr(x+8,y+15,6.5,7.5,3,'#fff');rr(x+w-14.5,y+15,6.5,7.5,3,'#fff');
    ctx.fillStyle=pal.ink;ctx.fillRect(x+11,y+18,3,3.4);ctx.fillRect(x+w-11.5,y+18,3,3.4);
    /* tiny A */
    ctx.strokeStyle='rgba(255,255,255,.95)';ctx.lineWidth=2.2;ctx.lineJoin='round';
    ctx.beginPath();ctx.moveTo(cx-5,y+bh+1);ctx.lineTo(cx,y+bh-9);ctx.lineTo(cx+5,y+bh+1);ctx.moveTo(cx-2.6,y+bh-3.4);ctx.lineTo(cx+2.6,y+bh-3.4);ctx.stroke();
    ctx.restore();
  }
  function draw(){
    if(!W||!H)return;
    ctx.save();
    ctx.clearRect(0,0,W,H);
    var sky=ctx.createLinearGradient(0,0,0,H);
    sky.addColorStop(0,mix(pal.brand,pal.bg,.86));sky.addColorStop(1,mix(pal.brand,pal.bg,.95));
    ctx.fillStyle=sky;ctx.fillRect(0,0,W,H);
    if(shake>0)ctx.translate((Math.random()-.5)*shake,(Math.random()-.5)*shake);
    clouds.forEach(cloud);
    hills(dist*.18,ground-8,H*.22,70,pal.brand,.13);
    hills(dist*.38+40,ground+2,H*.13,48,pal.brand,.2);
    /* ground */
    ctx.fillStyle=mix(pal.brand,pal.bg,.8);ctx.fillRect(0,ground,W,H-ground);
    ctx.fillStyle=pal.brand;ctx.globalAlpha=.35;ctx.fillRect(0,ground,W,2);ctx.globalAlpha=1;
    ctx.fillStyle=pal.mute;ctx.globalAlpha=.4;
    for(var i=0;i<9;i++){var gx=((i*74-dist*1.0)%(74*9)+74*9)%(74*9)-20;ctx.fillRect(gx,ground+14,22,2);ctx.fillRect(gx+36,ground+26,10,2)}
    ctx.globalAlpha=1;
    coins.forEach(drawCoin);obstacles.forEach(drawBox);
    puffs.forEach(function(p){ctx.globalAlpha=Math.max(0,p.l/p.m)*.7;ctx.fillStyle=p.c;ctx.beginPath();ctx.arc(p.x,p.y,p.r,0,6.283);ctx.fill()});ctx.globalAlpha=1;
    drawPlayer();
    if(flash>0){ctx.globalAlpha=flash;ctx.fillStyle=pal.brand;ctx.fillRect(0,0,W,H);ctx.globalAlpha=1}
    ctx.restore();
  }

  /* ---- game flow ---- */
  function puff(x,y,n,col,spread){for(var i=0;i<n;i++)puffs.push({x:x,y:y,vx:(Math.random()-.5)*spread,vy:-Math.random()*spread*.6,r:2+Math.random()*2.5,l:22,m:22,c:col})}
  function reset(){
    score=0;speed=5;dist=0;nextAt=380;obstacles=[];coins=[];puffs=[];shake=0;flash=0;t=0;
    P.y=ground-P.h;P.vy=0;P.ground=true;P.sq=0;scoreEl.textContent='0';
  }
  function showOverlay(kind){
    overlay.classList.remove('hidden');pauseBtn.hidden=true;
    res.classList.toggle('on',kind==='over');nb.classList.remove('on');
    if(kind==='ready'){headline.textContent='Ready to run?';message.textContent='Jump over the parcels and collect the coins.';startBtn.textContent='Play'}
    if(kind==='pause'){headline.textContent='Paused';message.textContent='Take a break. Your run is waiting.';startBtn.textContent='Resume'}
  }
  function start(){
    cancelAnimationFrame(raf);reset();mode='playing';overlay.classList.add('hidden');pauseBtn.hidden=false;last=performance.now();raf=requestAnimationFrame(loop);
  }
  function pause(){
    if(mode==='playing'){mode='paused';cancelAnimationFrame(raf);showOverlay('pause')}
    else if(mode==='paused'){mode='playing';overlay.classList.add('hidden');pauseBtn.hidden=false;last=performance.now();raf=requestAnimationFrame(loop)}
  }
  function end(){
    mode='over';var s=Math.floor(score),isNew=s>best&&s>0;
    if(s>best){best=s;try{localStorage.setItem('aeroRunBest',String(best))}catch(e){}}
    bestEl.textContent=best;rs.textContent=s;rb.textContent=best;
    shake=10;try{navigator.vibrate&&navigator.vibrate(35)}catch(e){}
    pauseBtn.hidden=true;requestAnimationFrame(idle);
    setTimeout(function(){
      if(mode!=='over')return;
      overlay.classList.remove('hidden');res.classList.add('on');nb.classList.toggle('on',isNew);
      headline.textContent=isNew?'New best!':'Nice run!';
      message.textContent=isNew?'You beat your own record.':'Give it another go and beat your best.';
      startBtn.textContent='Play again';
    },420);
  }
  function jump(){
    if(mode==='ready'||mode==='over'){if(mode==='over'&&!overlay.classList.contains('hidden')){start()}else if(mode==='ready'){start()}return}
    if(mode!=='playing')return;
    if(P.ground){P.vy=JUMP;P.ground=false;P.sq=0;held=true;puff(P.x+P.w/2,ground,6,pal.mute,3)}
  }
  function release(){held=false;if(mode==='playing'&&!P.ground&&P.vy<-5)P.vy=-5}
  function spawn(){
    var n=Math.random()<.28?2:1,x=W+30,total=0,i;
    for(i=0;i<n;i++){
      var w=24+Math.random()*10,h=26+Math.random()*(n>1?14:24);
      obstacles.push({x:x+total,y:ground-h,w:w,h:h});total+=w+2;
    }
    if(Math.random()<.6){
      var cx=x+total/2,cnt=3;
      for(i=0;i<cnt;i++){var k=(i-1)/1;coins.push({x:cx+k*26,y:ground-78-(1-k*k)*30})}
    }
    nextAt=dist+speed*(34+Math.random()*26)+total+60;
  }
  function hit(a,b){return a.x+6<b.x+b.w-3&&a.x+a.w-6>b.x+3&&a.y+8<b.y+b.h-2&&a.y+a.h-3>b.y+2}
  var lastMile=0;
  function loop(now){
    if(mode!=='playing')return;
    var dt=Math.min((now-last)/16.67,2);last=now;t+=dt;
    speed=Math.min(10.5,5+score/110);dist+=speed*dt;score+=.11*dt*(speed/5);
    var was=P.ground;
    P.vy+=G*dt*(held&&P.vy<0?.78:1);P.y+=P.vy*dt;
    if(P.y>=ground-P.h){P.y=ground-P.h;P.vy=0;P.ground=true;if(!was){P.sq=1;puff(P.x+P.w/2,ground,7,pal.mute,3.4)}}
    P.sq=Math.max(0,P.sq-.09*dt);
    if(dist>=nextAt)spawn();
    var mv=speed*dt,i;
    for(i=0;i<obstacles.length;i++)obstacles[i].x-=mv;
    for(i=0;i<coins.length;i++)coins[i].x-=mv;
    obstacles=obstacles.filter(function(o){return o.x+o.w>-10});
    coins=coins.filter(function(c){return c.x>-20});
    for(i=coins.length-1;i>=0;i--){
      var c=coins[i];
      if(Math.abs((P.x+P.w/2)-c.x)<24&&Math.abs((P.y+P.h/2)-c.y)<28){score+=10;puff(c.x,c.y,9,pal.accent,5);coins.splice(i,1)}
    }
    puffs.forEach(function(p){p.x+=p.vx*dt;p.y+=p.vy*dt;p.vy+=.12*dt;p.l-=dt});puffs=puffs.filter(function(p){return p.l>0});
    clouds.forEach(function(c){c.x-=.22*dt;if(c.x<-50)c.x=W+40});
    if(shake>0)shake=Math.max(0,shake-.8*dt);
    var mile=Math.floor(score/100);if(mile>lastMile){flash=.16}lastMile=mile;if(flash>0)flash=Math.max(0,flash-.012*dt);
    for(i=0;i<obstacles.length;i++){if(hit(P,obstacles[i])){end();draw();return}}
    scoreEl.textContent=Math.floor(score);draw();raf=requestAnimationFrame(loop);
  }
  /* an idle scene behind the start card */
  function idle(){if(mode==='playing')return;t+=1;if(shake>0)shake=Math.max(0,shake-.5);draw();if(mode!=='playing')requestAnimationFrame(idle)}

  startBtn.addEventListener('click',function(e){e.stopPropagation();if(mode==='paused')pause();else start()});
  pauseBtn.addEventListener('click',function(e){e.stopPropagation();pause()});
  stage.addEventListener('pointerdown',function(e){if(e.target===pauseBtn||pauseBtn.contains(e.target)||(e.target.closest&&e.target.closest('.panel')))return;e.preventDefault();jump()});
  addEventListener('pointerup',release);addEventListener('pointercancel',release);
  addEventListener('keydown',function(e){
    if(e.code==='Space'||e.code==='ArrowUp'){e.preventDefault();if(!e.repeat)jump()}
    else if(e.code==='Escape'||e.code==='KeyP')pause();
  });
  addEventListener('keyup',function(e){if(e.code==='Space'||e.code==='ArrowUp')release()});
  document.addEventListener('visibilitychange',function(){if(document.hidden&&mode==='playing')pause()});
  try{best=Number(localStorage.getItem('aeroRunBest'))||0}catch(e){}
  bestEl.textContent=best;
  offline();
  if('ResizeObserver' in window)new ResizeObserver(resize).observe(stage);else addEventListener('resize',resize);
  readPal();resize();reset();idle();
})();
</script>
</body>
</html>
""";

    WebView web;
    String failedUrl = null;
    FrameLayout root;
    SharedPreferences prefs;
    Handler h = new Handler(Looper.getMainLooper());

    volatile String pendingKey = null; // app link the user tapped before a MediaFire page opened
    String lastJson = null;
    boolean polling = false;
    boolean active = false;
    int topInsetPx = 0;

    /* package added / removed / replaced -> page re-checks which apps are installed (Open / Uninstall buttons) */
    final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i == null ? null : i.getAction();
            if (i != null && i.getData() != null
                    && (Intent.ACTION_PACKAGE_ADDED.equals(a) || Intent.ACTION_PACKAGE_REPLACED.equals(a))) {
                onPkgInstalled(i.getData().getSchemeSpecificPart());
            }
            h.postDelayed(() -> refreshWeb(), 400);
        }
    };

    final Runnable tick = new Runnable() {
        @Override public void run() {
            push(false);
            // keep pushing while an install is running too, so the button keeps saying "Installing..."
            if (active || !installing.isEmpty()) {
                h.postDelayed(this, 500);
            } else {
                polling = false;
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Downloader.init(this);
        prefs = getSharedPreferences("dl", MODE_PRIVATE);
        try {
            IntentFilter pf = new IntentFilter();
            pf.addAction(Intent.ACTION_PACKAGE_ADDED);
            pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
            pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
            pf.addDataScheme("package");
            registerReceiver(pkgReceiver, pf);
        } catch (Throwable t) { }

        transparentBars();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            topInsetPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            // keep the page above the on-screen keyboard
            root.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            applyInsets();
            return insets; // not consumed, WebView sits under the bar
        });
        setContentView(root);
        transparentBars(); // again after the content view exists

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AeroBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("http")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception e) { }
                return true;
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    String eu = r.getUrl().toString();
                    if (eu.startsWith(OFFLINE_BASE)) return; // the game itself: never loop
                    failedUrl = eu;
                    v.loadDataWithBaseURL(OFFLINE_BASE, OFFLINE_HTML, "text/html", "UTF-8", null);
                }
            }
            @Override public void onPageFinished(WebView v, String url) {
                applyInsets();
                push(true);
            }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> startFromWeb(url, ua, cd, mime));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(SITE);
        hideNav();
    }

    /* Fully transparent status/navigation bars (no grey scrim); page draws behind them, icons stay visible. */
    void transparentBars() {
        android.view.Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        WindowCompat.setDecorFitsSystemWindows(w, false);
        // legacy flags: some OEM skins (MIUI etc.) only honour these for a truly transparent bar
        // applied on every SDK (not only < 30): MIUI / One UI still need it on Android 11+
        View dv = w.getDecorView();
        dv.setSystemUiVisibility(dv.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        // draw into the notch / cutout area so no black strip appears behind the status bar
        if (Build.VERSION.SDK_INT >= 28) {
            w.getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
    }

    /* Tell the page how tall the status bar is (CSS var --sat) and make the bar icons follow the page theme. */
    void applyInsets() {
        if (web == null) return;
        float d = getResources().getDisplayMetrics().density;
        String js = "(function(){try{"
            + "document.documentElement.style.setProperty('--sat','" + (topInsetPx / d) + "px');"
            + "if(window.__aeroBar)return;window.__aeroBar=1;"
            + "function f(){try{var t=document.createElement('div');"
            + "t.style.cssText='display:none;background:var(--bg,#fff)';document.documentElement.appendChild(t);"
            + "var c=getComputedStyle(t).backgroundColor.match(/[\\d.]+/g);t.remove();"
            + "var l=0.299*c[0]+0.587*c[1]+0.114*c[2];AeroBridge.bar(l<140);}catch(e){}}"
            + "f();new MutationObserver(f).observe(document.documentElement,{attributes:true});"
            + "var m=matchMedia('(prefers-color-scheme:dark)');"
            + "if(m.addEventListener)m.addEventListener('change',f);"
            + "}catch(e){}})()";
        web.evaluateJavascript(js, null);
    }

    /* Keep the status bar visible, hide the navigation bar (swipe from the edge shows it briefly). */
    void hideNav() {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (c == null) return;
        c.hide(WindowInsetsCompat.Type.navigationBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) { transparentBars(); hideNav(); }
    }

    @Override protected void onResume() {
        super.onResume();
        transparentBars();
        hideNav();
        push(true);
        ensurePolling();
        PairingService.listener = p -> h.post(() -> showCodeDialog(p));
        handlePairIntent();
        // back from the installer / uninstall: let the page re-check which apps are installed
        if (web != null) web.evaluateJavascript("window.aeroRefresh&&window.aeroRefresh()", null);
        // installer closed without installing (cancelled): the button goes back to Install
        if (!installing.isEmpty()) h.postDelayed(() -> {
            if (wirelessBusy == 0 && !installing.isEmpty()) { installing.clear(); push(true); }
        }, 2500);
    }

    @Override protected void onPause() {
        PairingService.listener = null;
        super.onPause();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handlePairIntent();
    }

    @Override public void onBackPressed() {
        String cu = web.getUrl();
        if (cu != null && cu.startsWith(OFFLINE_BASE)) super.onBackPressed(); // leave from the game
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        alive = false;
        h.removeCallbacks(tick);
        try { unregisterReceiver(pkgReceiver); } catch (Throwable t) { }
        super.onDestroy();
    }

    /* ---------- bridge exposed to the web page ---------- */

    class Bridge {
        @JavascriptInterface public void start(final String key, final String name) {
            h.post(() -> startDirect(key, name));
        }
        @JavascriptInterface public void cancel(final String key) {
            h.post(() -> removeKey(key));
        }
        @JavascriptInterface public void install(final String key) {
            h.post(() -> installKey(key));
        }
        @JavascriptInterface public void retry() {
            h.post(() -> web.loadUrl(failedUrl != null ? failedUrl : SITE));
        }
        @JavascriptInterface public void showKeyboard() {
            h.post(() -> {
                web.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            });
        }
        @JavascriptInterface public void bar(final boolean darkPage) {
            h.post(() -> {
                WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                if (c != null) c.setAppearanceLightStatusBars(!darkPage);
            });
        }
        /* the store page tells the app its theme; the offline game reads it back */
        @JavascriptInterface public void saveTheme(String t) {
            if (t != null && t.matches("dark|light|green|glass|auto")) prefs.edit().putString("theme", t).apply();
        }
        @JavascriptInterface public String getTheme() {
            return prefs.getString("theme", "auto");
        }
        @JavascriptInterface public void mark(String key) {
            pendingKey = key;
        }
        @JavascriptInterface public void pairWireless() {
            h.post(() -> showPairDialog());
        }
        @JavascriptInterface public boolean isPaired() {
            return prefs.getBoolean("wpaired", false);
        }
        /* true when finished downloads install by themselves (Wireless debugging ready): the page then shows "Installing..." at once */
        @JavascriptInterface public boolean isSilent() {
            return silentReady();
        }
        @JavascriptInterface public String getInstallMode() {
            return installMode();
        }
        @JavascriptInterface public void setInstallMode(final String mode) {
            h.post(() -> chooseInstallMode(mode));
        }
        @JavascriptInterface public String state() {
            return buildState().toString();
        }
        /* pkgsJson: ["com.a","com.b"] -> JSON array of the ones installed on this phone */
        @JavascriptInterface public String installed(String pkgsJson) {
            return installedOf(pkgsJson);
        }
        @JavascriptInterface public void open(final String pkg) {
            h.post(() -> openPkg(pkg));
        }
        @JavascriptInterface public void uninstall(final String pkg) {
            h.post(() -> uninstallPkg(pkg));
        }
        /* extra options of the Wireless method: {"tcp":bool,"port":int,"legacy":bool} */
        @JavascriptInterface public String getWirelessOpts() {
            JSONObject o = new JSONObject();
            try {
                o.put("tcp", tcpOn());
                o.put("port", prefs.getInt("tcpport", 5555));
                o.put("legacy", prefs.getBoolean("legacy", false));
            } catch (Exception e) { }
            return o.toString();
        }
        @JavascriptInterface public void setTcpMode(final boolean on) {
            // saved right now (not inside h.post): the page re-reads getWirelessOpts() the moment this returns,
            // so a delayed write made the switch show the old value and look broken
            prefs.edit().putBoolean("tcp", on).apply();
            h.post(() -> {
                optsRefresh();
                if (on) applyTcp();
            });
        }
        @JavascriptInterface public void setLegacyPairing(final boolean on) {
            prefs.edit().putBoolean("legacy", on).apply();
        }
        @JavascriptInterface public void editTcpPort() {
            h.post(() -> showPortDialog());
        }
    }

    /* ---------- Wireless options: TCP mode, TCP port, Legacy pairing ---------- */

    AlertDialog codeDlg;

    /* TCP mode is on unless the person switched it off: a connection through the local TCP port does not make
       Android show "Wireless debugging connected" again and again. */
    boolean tcpOn() { return prefs.getBoolean("tcp", true); }

    void optsRefresh() {
        if (web != null) web.evaluateJavascript("window.aeroOpts&&window.aeroOpts()", null);
    }

    void handlePairIntent() {
        Intent i = getIntent();
        if (i == null) return;
        int p = i.getIntExtra("pairPort", -1);
        if (p >= 0) {
            i.removeExtra("pairPort");
            showCodeDialog(p);
        }
    }

    FrameLayout padded(View v) {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(v);
        return box;
    }

    void stopPairingService() {
        try { startService(new Intent(this, PairingService.class).setAction(PairingService.ACTION_STOP)); } catch (Throwable t) { }
    }

    /* Legacy pairing: the 6-digit code is typed in this dialog instead of a notification. */
    void showCodeDialog(final int port) {
        if (isFinishing()) return;
        if (codeDlg != null && codeDlg.isShowing()) return;
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("6-digit pairing code");
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        codeDlg = new AlertDialog.Builder(this)
                .setTitle("Pairing code")
                .setMessage("Type the code shown in the Wireless debugging pairing pop-up.")
                .setView(padded(et))
                .setPositiveButton("Pair", (d, w) -> pairWithCode(port, et.getText().toString().trim()))
                .setNegativeButton("Cancel", (d, w) -> stopPairingService())
                .create();
        codeDlg.show();
    }

    void pairWithCode(final int port, final String code) {
        if (code.isEmpty()) { showCodeDialog(port); return; }
        Toast.makeText(this, "Pairing...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = false;
            String msg = "";
            try {
                ok = AdbManager.get(this).pair("127.0.0.1", port, code);
            } catch (Throwable t) {
                msg = String.valueOf(t.getMessage());
            }
            final boolean fok = ok;
            final String fmsg = msg;
            h.post(() -> {
                if (fok) {
                    prefs.edit().putBoolean("wpaired", true).apply();
                    stopPairingService();
                    Toast.makeText(this, "Paired successfully", Toast.LENGTH_LONG).show();
                    optsRefresh();
                    if (tcpOn()) applyTcp();
                } else {
                    Toast.makeText(this, "Pairing failed" + (fmsg.isEmpty() ? "" : " (" + fmsg + ")")
                            + ". Check the code and try again.", Toast.LENGTH_LONG).show();
                    showCodeDialog(port);
                }
            });
        }).start();
    }

    void showPortDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(prefs.getInt("tcpport", 5555)));
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("TCP port")
                .setView(padded(et))
                .setPositiveButton("Save", (d, w) -> {
                    int v = -1;
                    try { v = Integer.parseInt(et.getText().toString().trim()); } catch (Exception e) { }
                    if (v < 1024 || v > 65535) {
                        Toast.makeText(this, "Enter a port between 1024 and 65535", Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs.edit().putInt("tcpport", v).apply();
                    optsRefresh();
                    if (tcpOn()) applyTcp();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /* Connect to adbd.
       1) TCP mode: through 127.0.0.1:<port> on this phone. Works without Wi-Fi and Android shows NO
          "Wireless debugging connected" notification for it.
       2) Otherwise through Wireless debugging (Android shows its notification for this one). When TCP mode is on,
          adbd is then told to listen on the TCP port, so every next connection uses way 1 and stays silent. */
    boolean connectTcp(AdbManager m, int port, int tries) {
        for (int i = 0; i < tries; i++) {
            try { if (m.connect("127.0.0.1", port)) return true; } catch (Throwable t) { }
            if (i + 1 < tries) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
            }
        }
        return false;
    }

    /* runs one shell command over the open connection and returns what it printed */
    String shell(AdbManager m, String cmd) {
        AdbStream st = null;
        try {
            st = m.openStream("shell:" + cmd);
            return readAll(st.openInputStream());
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (st != null) st.close(); } catch (Throwable t) { }
        }
    }

    /* true when the open connection is the TCP one, and the reason when TCP mode could not start (shown in a toast) */
    volatile boolean viaTcp = false;
    volatile String tcpNote = "";

    /* asks adbd (over the connection that is open now) to also listen on the TCP port; adbd restarts, so the connection ends.
       Returns true when the request was sent. "tcpip" goes FIRST: the old order ran "settings put global adb_enabled 1"
       before it, and that can make Android restart adbd and cut the connection, so "tcpip" was never sent at all. */
    boolean switchToTcp(AdbManager m, int port) {
        boolean sent = false;
        AdbStream st = null;
        try {
            st = m.openStream("tcpip:" + port);
            sent = true;
            try { readAll(st.openInputStream()); } catch (Throwable ignore) { } // adbd restarts, the stream may just end
        } catch (Throwable t) {
            tcpNote = "adbd did not accept tcpip (" + t.getMessage() + ")";
        } finally {
            try { if (st != null) st.close(); } catch (Throwable t) { }
        }
        try { m.disconnect(); } catch (Throwable t) { }
        return sent;
    }

    /* USB debugging switch in Developer options; readable by any app */
    boolean usbDebuggingOn() {
        try { return Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1; }
        catch (Throwable t) { return false; }
    }

    boolean connectAdb(AdbManager m) throws Exception {
        final boolean tcp = tcpOn();
        final int port = prefs.getInt("tcpport", 5555);
        viaTcp = false;
        tcpNote = "";
        if (tcp && connectTcp(m, port, 2)) { viaTcp = true; return true; }
        if (!m.autoConnect(this, 10000)) return false;
        if (!tcp) return true;
        boolean sent = switchToTcp(m, port);
        // adbd needs a few seconds to restart on the new port (8 tries x 0.5 s was too short on slower phones)
        if (sent && connectTcp(m, port, 20)) {
            viaTcp = true;
            // While USB debugging is on, Android keeps adbd (and so the TCP port) alive even when Wi-Fi is turned off.
            // Done now, over the TCP connection, and only when it is still off.
            if (!usbDebuggingOn()) {
                shell(m, "settings put global adb_enabled 1");
                if (!m.isConnected() && !connectTcp(m, port, 10)) {
                    viaTcp = false;
                    tcpNote = "the connection dropped after switching USB debugging on";
                    return m.autoConnect(this, 10000);
                }
            }
            return true;
        }
        if (sent && tcpNote.isEmpty()) {
            tcpNote = "adbd restarted but port " + port + " did not open. If Android shows an \"Allow USB debugging?\" pop-up, tap Always allow.";
        }
        return m.autoConnect(this, 10000); // TCP did not come up: stay on Wireless debugging
    }

    /* TCP mode switched on / port changed: reconnect now, so adbd moves to the TCP port. */
    void applyTcp() {
        if (!tcpOn()) return;
        if (!prefs.getBoolean("wpaired", false)) {
            Toast.makeText(this, "Pair Wireless debugging first, then TCP mode will switch on", Toast.LENGTH_LONG).show();
            return;
        }
        final int port = prefs.getInt("tcpport", 5555);
        Toast.makeText(this, "Switching on TCP mode...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = false;
            String msg = "";
            synchronized (adbLock) {
                try {
                    AdbManager m = AdbManager.get(this);
                    try { if (m.isConnected()) m.disconnect(); } catch (Throwable t) { }
                    ok = ensureAdb(m);
                    if (!ok) msg = "Turn on Wireless debugging and connect to Wi-Fi first.";
                } catch (Throwable t) {
                    msg = String.valueOf(t.getMessage());
                }
            }
            final boolean fok = ok;
            final boolean ftcp = viaTcp;
            final String fmsg = msg.isEmpty() ? tcpNote : msg;
            // "on" only when the connection really is the TCP one (before: any Wireless connection said "TCP mode on")
            h.post(() -> Toast.makeText(this, !fok ? "TCP mode failed" + (fmsg.isEmpty() ? "" : ": " + fmsg)
                    : ftcp ? "TCP mode on, port " + port
                    : "TCP mode did not start, still using Wireless debugging" + (fmsg.isEmpty() ? "" : ": " + fmsg),
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    /* ---------- installed apps / open ---------- */

    String installedOf(String pkgsJson) {
        org.json.JSONArray out = new org.json.JSONArray();
        try {
            org.json.JSONArray in = new org.json.JSONArray(pkgsJson);
            PackageManager pm = getPackageManager();
            for (int i = 0; i < in.length(); i++) {
                String p = in.optString(i, "");
                if (p.isEmpty()) continue;
                try { pm.getPackageInfo(p, 0); out.put(p); } catch (PackageManager.NameNotFoundException e) { }
            }
        } catch (Exception e) { }
        return out.toString();
    }

    void openPkg(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) { Toast.makeText(this, "Can't open this app", Toast.LENGTH_LONG).show(); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open this app", Toast.LENGTH_LONG).show();
        }
    }

    /* Uninstall: Android shows its own confirm dialog; when it closes the page refreshes (onResume + package receiver). */
    void uninstallPkg(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Can't uninstall this app", Toast.LENGTH_LONG).show();
        }
    }

    void refreshWeb() {
        if (web != null) web.evaluateJavascript("window.aeroRefresh&&window.aeroRefresh()", null);
    }

    /* ---------- downloads: done inside the app by Downloader (not the phone's Download Manager) ---------- */

    File dir() {
        return getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    }

    void startDirect(String key, String name) {
        startDl(key, key, name, null, null);
    }

    // MediaFire (and any other page) hands the file to the WebView's download listener.
    void startFromWeb(String url, String ua, String cd, String mime) {
        String name = URLUtil.guessFileName(url, cd, mime);
        String key = pendingKey != null ? pendingKey : url;
        pendingKey = null;
        startDl(key, url, name, ua, CookieManager.getInstance().getCookie(url));
        String cur = web.getUrl();
        if (cur != null && cur.contains("mediafire.com") && web.canGoBack()) web.goBack();
    }

    void startDl(String key, String url, String name, String ua, String cookie) {
        try {
            Downloader.start(this, key, url, name, ua, cookie);
        } catch (Exception e) {
            Toast.makeText(this, "Download failed to start", Toast.LENGTH_LONG).show();
            return;
        }
        autoQueueAdd(key); // install by itself when this download finishes (Wireless mode)
        warmAdb();
        push(true);
        ensurePolling();
    }

    void removeKey(String key) {
        Downloader.remove(key);
        autoQueueRemove(key);
        push(true);
    }

    /* ---------- "Installing..." / "Installed" shown on the Download button ---------- */

    final Map<String, Long> installing = new ConcurrentHashMap<>();   // key -> when the install started
    final Map<String, Long> installedAt = new ConcurrentHashMap<>();  // key -> when it finished (shown ~2.5 s)
    volatile int wirelessBusy = 0;

    void markInstalling(String key) {
        if (key == null) return;
        installedAt.remove(key);
        installing.put(key, System.currentTimeMillis());
    }

    /* the install of this app finished: the button says "Installed" for a moment, then turns into Open */
    void markInstalled(String key) {
        if (key == null || installing.remove(key) == null) return;
        installedAt.put(key, System.currentTimeMillis());
        push(true);
        h.postDelayed(() -> push(true), 2700);
    }

    /* a package was added / replaced on the phone: find which of our downloads it belongs to */
    void onPkgInstalled(String pkg) {
        if (pkg == null || installing.isEmpty()) return;
        for (String k : new ArrayList<>(installing.keySet())) {
            try {
                String local = Downloader.localUri(k);
                if (local == null) continue;
                android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(Uri.parse(local).getPath(), 0);
                if (pi != null && pkg.equals(pi.packageName)) markInstalled(k);
            } catch (Throwable t) { }
        }
    }

    /* put status "installing" / "installed" over finished downloads, so the page can show it on the button */
    void overlayInstall(JSONObject out) {
        long now = System.currentTimeMillis();
        try {
            for (Map.Entry<String, Long> e : new ArrayList<>(installing.entrySet())) {
                if (now - e.getValue() > 3 * 60 * 1000L) { installing.remove(e.getKey()); continue; }
                JSONObject o = out.optJSONObject(e.getKey());
                if (o != null && "done".equals(o.optString("status"))) o.put("status", "installing");
            }
            for (Map.Entry<String, Long> e : new ArrayList<>(installedAt.entrySet())) {
                if (now - e.getValue() > 2500) { installedAt.remove(e.getKey()); continue; }
                JSONObject o = out.optJSONObject(e.getKey());
                if (o != null && "done".equals(o.optString("status"))) o.put("status", "installed");
            }
        } catch (Exception e) { }
    }

    /* ---------- state -> web page ---------- */

    JSONObject buildState() {
        JSONObject out = Downloader.state();
        List<String> keys = new ArrayList<>();
        Iterator<String> it = out.keys();
        while (it.hasNext()) keys.add(it.next());
        boolean act = false;
        for (String k : keys) {
            String st = out.optJSONObject(k).optString("status");
            if ("running".equals(st) || "paused".equals(st)) act = true;
            else if ("done".equals(st)) maybeAutoInstall(k, Downloader.localUri(k));
            else if ("failed".equals(st)) autoQueueRemove(k);
        }
        active = act;
        overlayInstall(out);
        return out;
    }

    void push(boolean force) {
        if (web == null) return;
        String json = buildState().toString();
        if (!force && json.equals(lastJson)) return;
        lastJson = json;
        web.evaluateJavascript("window.aeroOnState&&window.aeroOnState(" + json + ")", null);
    }

    void ensurePolling() {
        if (polling) return;
        polling = true;
        h.removeCallbacks(tick);
        h.postDelayed(tick, 500);
    }

    /* ---------- auto install (Wireless debugging) ---------- */

    /* downloads started in this app that should install by themselves once finished */
    synchronized void autoQueueAdd(String key) {
        Set<String> s = new HashSet<>(prefs.getStringSet("autoq", new HashSet<String>()));
        s.add(key);
        prefs.edit().putStringSet("autoq", s).apply();
    }

    synchronized boolean autoQueueRemove(String key) {
        Set<String> s = new HashSet<>(prefs.getStringSet("autoq", new HashSet<String>()));
        boolean had = s.remove(key);
        if (had) prefs.edit().putStringSet("autoq", s).apply();
        return had;
    }

    synchronized boolean autoQueueHas(String key) {
        return prefs.getStringSet("autoq", new HashSet<String>()).contains(key);
    }

    /* true when the chosen method (Wireless debugging) can install without the installer screen right now */
    boolean silentReady() {
        String mode = installMode();
        if ("wireless".equals(mode)) return Build.VERSION.SDK_INT >= 30 && prefs.getBoolean("wpaired", false);
        return false;
    }

    /* Called when a download is finished. Installs it at once (no extra tap) if Wireless debugging is ready;
       otherwise the page keeps showing the Install button as before. Runs only once per download. */
    void maybeAutoInstall(String key, final String local) {
        if (local == null || !autoQueueHas(key)) return;
        boolean silent = silentReady();
        if (!autoQueueRemove(key)) return; // already handled by another call
        if (!silent) return;
        markInstalling(key);
        h.post(() -> {
            push(true);        // tell the page "installing" first ...
            ensurePolling();   // ... and keep telling it until the install ends
            install(key, local);
        });
    }

    /* ---------- install ---------- */

    void installKey(String key) {
        String local = Downloader.localUri(key);
        if (local == null) return;
        markInstalling(key);
        push(true);
        install(key, local);
    }

    /* ---------- install method chosen in the menu: "normal" or "wireless" ---------- */

    String installMode() {
        String m = prefs.getString("mode", "normal");
        return "wireless".equals(m) ? m : "normal";
    }

    void chooseInstallMode(String mode) {
        if ("wireless".equals(mode)) {
            if (Build.VERSION.SDK_INT < 30) {
                Toast.makeText(this, "Wireless debugging needs Android 11 or newer", Toast.LENGTH_LONG).show();
                prefs.edit().putString("mode", "normal").apply();
                return;
            }
            prefs.edit().putString("mode", "wireless").apply();
            if (!prefs.getBoolean("wpaired", false)) showPairDialog();
            else {
                Toast.makeText(this, "Wireless debugging mode on", Toast.LENGTH_SHORT).show();
                if (tcpOn()) applyTcp();
            }
            return;
        }
        prefs.edit().putString("mode", "normal").apply();
    }

    /* Pick the installer: Wireless debugging (silent) when chosen, otherwise the normal Android installer. */
    void install(String key, String localUri) {
        if (localUri == null) return;
        if ("wireless".equals(installMode())) { wirelessInstall(key, localUri); return; }
        normalInstall(localUri);
    }

    static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toString("UTF-8");
    }

    /* ---------- Wireless debugging (own ADB connection) ---------- */

    /* One-time pairing: a notification takes the pairing code, so the Settings pop-up can stay open. */
    static final int REQ_NOTIF = 4712;

    void showPairDialog() { startPairing(); }

    void startPairing() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "Wireless debugging needs Android 11 or newer", Toast.LENGTH_LONG).show();
            return;
        }
        final boolean legacy = prefs.getBoolean("legacy", false);
        if (!legacy && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        try {
            startForegroundService(new Intent(this, PairingService.class).setAction(PairingService.ACTION_START));
        } catch (Throwable t) {
            Toast.makeText(this, "Could not start pairing: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (legacy) {
            // stay in the app: the code dialog appears here (use split-screen / a pop-up window for Settings)
            Toast.makeText(this, "Open Wireless debugging in split-screen or a pop-up window and tap \"Pair device with pairing code\". The code dialog will appear here.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Turn on Wireless debugging, tap \"Pair device with pairing code\", then type the code in the notification",
                Toast.LENGTH_LONG).show();
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); } catch (Throwable t) { }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_NOTIF) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startPairing();
        else Toast.makeText(this, "Allow notifications so you can type the pairing code", Toast.LENGTH_LONG).show();
    }

    /* Install over Wireless debugging: connect to adbd on this phone and stream the APK into "cmd package install". */
    /* ---------- one shared ADB connection ----------
       Every new connection makes Android show "Wireless debugging connected". So the store keeps ONE connection,
       installs go through it one after another, and it is kept alive (and opened already when a download starts)
       for as long as the store is being used. */

    final Object adbLock = new Object();
    volatile long adbLastUse = 0;
    volatile boolean keepAlive = false;
    volatile boolean alive = true;

    /* call with adbLock held */
    boolean ensureAdb(AdbManager m) throws Exception {
        if (!m.isConnected() && !connectAdb(m)) return false;
        adbLastUse = System.currentTimeMillis();
        startKeepAlive();
        return true;
    }

    /* tiny command every 15 s so the connection is not dropped while idle; stops 10 minutes after the last real use */
    void startKeepAlive() {
        if (keepAlive) return;
        keepAlive = true;
        new Thread(() -> {
            try {
                while (alive) {
                    Thread.sleep(15000);
                    if (System.currentTimeMillis() - adbLastUse > 30 * 60 * 1000L) break;
                    synchronized (adbLock) {
                        AdbStream s = null;
                        try {
                            AdbManager m = AdbManager.get(this);
                            if (!m.isConnected()) break;
                            s = m.openStream("shell:echo");
                            readAll(s.openInputStream());
                        } catch (Throwable t) {
                            break;
                        } finally {
                            try { if (s != null) s.close(); } catch (Throwable t) { }
                        }
                    }
                }
            } catch (InterruptedException e) { }
            keepAlive = false;
        }).start();
    }

    /* open the connection when a download starts, so it is ready (and announced only once) by the time the APK is done */
    void warmAdb() {
        if (!"wireless".equals(installMode()) || !silentReady()) return;
        new Thread(() -> {
            synchronized (adbLock) {
                try { ensureAdb(AdbManager.get(this)); } catch (Throwable t) { }
            }
        }).start();
    }

    void wirelessInstall(final String key, final String localUri) {
        if (!prefs.getBoolean("wpaired", false)) {
            Toast.makeText(this, "Pair Wireless debugging first", Toast.LENGTH_LONG).show();
            installing.remove(key);
            push(true);
            showPairDialog();
            return;
        }
        Toast.makeText(this, "Installing with Wireless debugging...", Toast.LENGTH_SHORT).show();
        wirelessBusy++;
        new Thread(() -> {
            boolean ok = false;
            String msg = "";
            synchronized (adbLock) { // installs run one after another over the same connection
                for (int attempt = 0; attempt < 2 && !ok; attempt++) {
                    AdbStream st = null;
                    boolean reused = false;
                    try {
                        File f = new File(Uri.parse(localUri).getPath());
                        AdbManager m = AdbManager.get(this);
                        reused = m.isConnected();
                        if (!ensureAdb(m)) {
                            throw new Exception("Could not connect. Turn on Wireless debugging and connect to Wi-Fi.");
                        }
                        st = m.openStream("exec:cmd package install -r -S " + f.length());
                        try (InputStream in = new FileInputStream(f)) {
                            OutputStream os = st.openOutputStream();
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                            os.flush();
                        }
                        String out = readAll(st.openInputStream());
                        ok = out.contains("Success");
                        msg = out.trim();
                        if (!ok) break; // the install itself said no: a new connection would not help
                    } catch (Throwable t) {
                        msg = String.valueOf(t.getMessage());
                        // an old connection may have died: drop it and connect again once
                        if (reused) { try { AdbManager.get(this).disconnect(); } catch (Throwable x) { } }
                        else break;
                    } finally {
                        try { if (st != null) st.close(); } catch (Throwable t) { }
                    }
                }
                adbLastUse = System.currentTimeMillis();
            }
            final boolean fok = ok;
            final String fmsg = msg;
            h.post(() -> {
                wirelessBusy--;
                if (fok) {
                    markInstalled(key);
                    refreshWeb();
                } else {
                    Toast.makeText(this, "Wireless install failed" + (fmsg.isEmpty() ? "" : ": " + fmsg)
                            + ". Using the normal installer.", Toast.LENGTH_LONG).show();
                    normalInstall(localUri);
                }
            });
        }).start();
    }

    void normalInstall(String localUri) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow installs from this app, then tap Install again", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            File f = new File(Uri.parse(localUri).getPath());
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fp", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open installer", Toast.LENGTH_LONG).show();
        }
    }
}
