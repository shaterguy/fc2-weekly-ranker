const fs = require("fs");
const assert = require("assert/strict");
const puppeteer = require("/data/data/com.termux/files/usr/lib/node_modules/puppeteer-core");
const source = fs.readFileSync("app/src/main/java/com/shaterguy/fc2weeklyranker/network/RankingHtmlFetch.kt", "utf8");
const match = source.match(/return """\n([\s\S]*?)\n    """\.trimIndent\(\)/);
assert(match, "Production start script must be extracted, not duplicated");
const template = match[1];
const origin = "https://fixture.invalid";
const board = origin + "/bbs/board.php?bo_table=javfc2&page=";
const html = "<!doctype html><html><body><form id='fboardlist'><div class='list-item'><h2><a href='/javfc2/42'>Neutral fixture</a></h2><span class='comments'>7</span><span>10</span></div></form><script>window.UNWANTED = (window.UNWANTED || 0) + 1</script><img src='/slow.png'><iframe src='/frame'></iframe></body></html>";
function startScript(url, slot, limit = 2000000) {
  return template.replaceAll("$encodedUrl", JSON.stringify(url))
    .replaceAll("$encodedSlot", JSON.stringify(slot))
    .replaceAll("$encodedReferer", JSON.stringify(origin + "/"))
    .replaceAll("$maxBodyChars", String(limit));
}
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const median = a => [...a].sort((x,y) => x-y)[Math.floor(a.length/2)];
(async () => {
  let browser;
  for (const port of [9230,9228]) {
    try { browser = await puppeteer.connect({browserURL:"http://127.0.0.1:" + port, timeout:4000}); break; }
    catch (_) {}
  }
  assert(browser, "Existing Chromium is required");
  const page = await browser.newPage();
  let subresources = 0, external = 0, requests = 0;
  try {
    await page.setRequestInterception(true);
    page.on("request", async request => {
      try {
        const url = new URL(request.url());
        requests++;
        if (url.origin !== origin) { external++; return await request.abort(); }
        if (url.pathname === "/slow.png" || url.pathname === "/frame") {
          subresources++;
          await delay(750);
          return await request.respond({status:200,contentType:url.pathname.endsWith(".png") ? "image/png" : "text/html",
            body:url.pathname.endsWith(".png") ? Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1S8AAAAASUVORK5CYII=","base64") : "<html>Neutral frame</html>"});
        }
        if (url.pathname === "/redirect") return await request.respond({status:302,headers:{location:"https://outside.invalid/never"}});
        if (url.pathname === "/forbidden") return await request.respond({status:403,contentType:"text/html",body:"Just a moment..."});
        if (url.pathname === "/json") return await request.respond({status:200,contentType:"application/json",body:"{}"});
        if (url.pathname === "/large") return await request.respond({status:200,contentType:"text/html",body:"x".repeat(1000)});
        if (url.pathname === "/pending") await delay(1500);
        await delay(30);
        await request.respond({status:200,contentType:"text/html",body:url.pathname === "/" ? "<html><body>Neutral session</body></html>" : html});
      } catch (_) {}
    });
    async function run(url, slot, limit) {
      const started = await page.evaluate(startScript(url,slot,limit));
      if (!started) return {started:false};
      const deadline = Date.now()+6000;
      let result;
      do {
        result = await page.evaluate(key=>window[key]?.result,slot);
        if (!result) await delay(25);
        assert(Date.now()<deadline,"HTML response deadline");
      } while (!result);
      await page.evaluate(key=>{ const state=window[key]; delete window[key]; if(state)state.controller.abort(); },slot);
      return result;
    }
    const navigationSamples=[],htmlSamples=[];
    for(let i=0;i<5;i++){
      let t=performance.now();
      await page.goto(board+i,{waitUntil:"load",timeout:6000});
      navigationSamples.push(performance.now()-t);
      await page.goto(origin+"/",{waitUntil:"load"});
      const before = subresources;
      t=performance.now();
      const response=await run(board+i,"__fc2RankingHtml"+i);
      htmlSamples.push(performance.now()-t);
      assert.equal(response.status,200);
      assert(response.body.includes("Neutral fixture"));
      assert.equal(subresources,before,"HTML-only fetch must not load images or iframes");
      assert.equal(await page.evaluate(()=>window.UNWANTED || 0),0,"Fetched scripts must not run");
    }
    assert.equal((await run(origin+"/forbidden","forbidden")).status,403);
    assert.equal((await run(origin+"/redirect","redirect")).status,0);
    assert.equal(external,0,"redirect:error must prevent outside requests");
    assert.equal((await run(origin+"/json","json")).status,0);
    assert.equal((await run(origin+"/large","large",100)).status,0);
    const before = requests;
    assert.equal((await run("https://outside.invalid/test","outside")).started,false);
    assert.equal(requests,before,"Origin rejection must happen before networking");
    await page.evaluate(startScript(origin+"/pending","pending"));
    const aborted = await page.evaluate(()=>{
      const state=window.pending; delete window.pending; state.controller.abort();
      return state.controller.signal.aborted && !window.pending;
    });
    assert(aborted,"Cancellation must abort and delete the response slot");
    await delay(50);
    assert.equal(await page.evaluate(()=>!!window.pending),false,"Late completion must not restore deleted state");
    const oldMedian=median(navigationSamples),newMedian=median(htmlSamples);
    assert(newMedian<oldMedian*0.5,"Delayed-resource fixture should remove at least half of navigation time");
    console.log(JSON.stringify({status:"PASS",navigationSamples,htmlSamples,oldMedian,newMedian,
      improvementPercent:100*(1-newMedian/oldMedian),subresourceRequests:subresources,externalRequests:external,
      scenarios:["fresh-html","no-images-frames","scripts-not-executed","http403","redirect-rejected","nonhtml","size-cap","origin-rejected","cancellation"]}));
  } finally {
    await page.close();
    await browser.disconnect();
  }
})().catch(error=>{ console.error(error);process.exitCode=1; });
