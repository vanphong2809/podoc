import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {mkdtemp, chmod, lstat, rm} from 'node:fs/promises';
import {startGateway} from './podoc-gateway.mjs';

const authority='porender-server.tail7c6a6a.ts.net:8443';
const serviceHost='f814e00e-9993-46fc-bb71-a7d36675d38e.localhost';
test('private application gateway routes and streams without dashboard cookies', async () => {
  const directory=await mkdtemp('/tmp/podoc-vpn-test-');
  await chmod(directory,0o700);
  let observed;
  const upstream=http.createServer(async (req,res)=>{
    let length=0; try {for await(const chunk of req) length+=chunk.length;} catch {res.destroy();return;}
    observed={headers:req.headers,path:req.url,length};
    if(req.url==='/redirect') res.writeHead(307,{location:'http://'+serviceHost+'/api/articles?next=1'}).end();
    else if(req.url==='/failure') res.writeHead(503).end('unavailable');
    else res.writeHead(200,{'content-type':'application/json','set-cookie':'dashboard=must-not-pass'}).end(JSON.stringify({length}));
  });
  await new Promise(resolve=>upstream.listen(0,'127.0.0.1',resolve));
  const socketPath=directory+'/app.sock';
  const gateway=await startGateway({socketPath,authority,serviceHost,upstreamPort:upstream.address().port,maxBodyBytes:2*1024*1024});
  const request=(opts={},body)=>new Promise((resolve,reject)=>{
    const req=http.request({socketPath,path:opts.path||'/api/articles/upload',method:body?'POST':'GET',...opts,headers:{host:authority,...opts.headers}},res=>{
      let value='';res.on('data',chunk=>value+=chunk);res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:value}));
    });req.on('error',reject);
    if(Array.isArray(body)){for(const chunk of body) req.write(chunk);req.end();}else req.end(body);
  });
  try {
    assert.equal((await lstat(socketPath)).mode & 0o777,0o600);
    let response=await request({path:'/api/articles/upload?test=1',headers:{authorization:'Bearer test-token',cookie:'dashboard=secret','x-forwarded-host':'attacker.invalid'}},Buffer.alloc(1024*1024,97));
    assert.equal(response.status,200);assert.equal(observed.length,1024*1024);assert.equal(observed.path,'/api/articles/upload?test=1');
    assert.equal(observed.headers.host,serviceHost);assert.equal(observed.headers.cookie,undefined);assert.equal(observed.headers.authorization,'Bearer test-token');
    assert.equal(observed.headers['x-forwarded-host'],authority);assert.equal(response.headers['set-cookie'],undefined);
    assert.equal((await request({headers:{host:'attacker.invalid'}})).status,421);
    assert.equal((await request({path:'//attacker.invalid/api'})).status,400);
    assert.equal((await request({headers:{host:'localhost','x-forwarded-proto':'https','x-forwarded-host':authority}})).status,200);
    response=await request({path:'/redirect'});assert.equal(response.status,307);assert.equal(response.headers.location,'https://'+authority+'/api/articles?next=1');
    assert.equal((await request({path:'/failure'})).status,503);
    assert.equal((await request({headers:{'content-length':3*1024*1024}})).status,413);
    const limited = await request({headers:{'content-length':3*1024*1024}});
    assert.match(JSON.parse(limited.body).detail, /chưa được lưu/);
    assert.equal((await request({},[Buffer.alloc(1024*1024),Buffer.alloc(1024*1024),Buffer.alloc(10)])).status,413);
    await assert.rejects(startGateway({socketPath,authority,serviceHost,upstreamPort:upstream.address().port}),/Listener already active/);
    console.log('Verified 1 MiB uploads, fixed app routing, cookie isolation, TLS authority, redirect rewriting, status propagation, body bounds and active-socket protection');
  } finally {
    await gateway.close();
    await new Promise(resolve=>upstream.close(resolve));
    await rm(directory,{recursive:true,force:true});
  }
});

test('streams a 1.2 GiB body with bounded memory and a long upload deadline', async () => {
  const directory=await mkdtemp('/tmp/podoc-large-upload-');
  await chmod(directory,0o700);
  const upstream=http.createServer(async(req,res)=>{
    let length=0;
    for await(const chunk of req) length+=chunk.length;
    res.end(JSON.stringify({length}));
  });
  await new Promise(resolve=>upstream.listen(0,'127.0.0.1',resolve));
  const socketPath=directory+'/app.sock';
  const gateway=await startGateway({socketPath,authority,serviceHost,upstreamPort:upstream.address().port});
  try {
    assert.ok(gateway.server.requestTimeout>=30*60*1000);
    const bytes=Math.floor(1.2*1024*1024*1024);
    const baseline=process.memoryUsage().rss;
    let peak=baseline;
    const req=http.request({socketPath,method:'POST',path:'/api/articles/upload',headers:{host:authority,'content-length':bytes}});
    const completed=new Promise((resolve,reject)=>{
      req.on('error',reject);
      req.on('response',res=>{
        let body='';res.on('data',chunk=>body+=chunk);
        res.on('end',()=>resolve({status:res.statusCode,body}));
      });
    });
    const chunk=Buffer.alloc(64*1024,97);
    let remaining=bytes;
    while(remaining>0){
      const part=chunk.subarray(0,Math.min(remaining,chunk.length));
      if(!req.write(part)) await once(req,'drain');
      remaining-=part.length;
      peak=Math.max(peak,process.memoryUsage().rss);
    }
    req.end();
    const result=await completed;
    assert.equal(result.status,200);
    assert.equal(JSON.parse(result.body).length,bytes);
    assert.ok(peak-baseline<128*1024*1024,`RSS grew by ${peak-baseline} bytes`);
  } finally {
    await gateway.close();
    await new Promise(resolve=>upstream.close(resolve));
    await rm(directory,{recursive:true,force:true});
  }
});
