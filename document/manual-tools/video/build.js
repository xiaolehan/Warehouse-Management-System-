// 视频合成管线：tts 生成逐行配音 + 时长表 → mux 合成分段 → concat 成片
// 用法：node build.js tts   （生成 /tmp/wms-video/tts/*.mp3 + timeline.json，录制前必跑）
//       node build.js mux   （录制完成后，合成 /tmp/wms-video/WMS视频教程-销售全流程.mp4）
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const TTS = '/tmp/ttsvenv/bin/edge-tts';
const TMP = '/tmp/wms-video';
const GAP = 0.45;            // 行间静音秒数（与录制驻留公式的 0.45 一致）
const VOICE = 'zh-CN-XiaoxiaoNeural';
const narr = JSON.parse(fs.readFileSync(path.join(ROOT, 'narration.json'), 'utf8'));

const probe = f => parseFloat(execFileSync('ffprobe',
  ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', f],
  { encoding: 'utf8' }).trim());

function run(cmd, args) {
  execFileSync(cmd, args, { stdio: ['ignore', 'ignore', 'inherit'] });
}

// ---------- tts：逐行 edge-tts → mp3 + ffprobe 时长 → timeline.json ----------
function tts() {
  const dir = `${TMP}/tts`;
  fs.mkdirSync(dir, { recursive: true });
  const timeline = {};
  for (const sc of narr.scenes) {
    timeline[sc.id] = [];
    sc.lines.forEach((line, i) => {
      const f = `${dir}/${sc.id}_${i}.mp3`;
      if (!fs.existsSync(f)) {
        for (let t = 1; ; t++) {
          try { execFileSync(TTS, ['--voice', narr.voice, '--text', line, '--write-media', f]); break; }
          catch (e) { if (t >= 3) throw e; console.log(`TTS_RETRY ${sc.id}_${i} #${t}`); }
        }
      }
      const d = probe(f);
      timeline[sc.id].push(d);
      console.log(`TTS ${sc.id}_${i} ${d.toFixed(2)}s`);
    });
  }
  fs.writeFileSync(`${TMP}/timeline.json`, JSON.stringify(timeline, null, 1));
  console.log('TTS_ALL_OK ->', `${TMP}/timeline.json`);
}

// ---------- 场景音频：逐行 mp3 + 行间 0.45s 静音 → wav；返回 [总时长, SRT 内容] ----------
const srtTime = t => {
  const ms = Math.round(t * 1000);
  const p = (n, w) => String(n).padStart(w, '0');
  return `${p(Math.floor(ms / 3600000), 2)}:${p(Math.floor(ms / 60000) % 60, 2)}:${p(Math.floor(ms / 1000) % 60, 2)},${p(ms % 1000, 3)}`;
};

function sceneAudio(sc) {
  const dir = `${TMP}/wav`;
  fs.mkdirSync(`${TMP}/srt`, { recursive: true });
  fs.mkdirSync(dir, { recursive: true });
  const gap = `${dir}/gap.wav`;
  run('ffmpeg', ['-y', '-v', 'error', '-f', 'lavfi', '-i', 'anullsrc=r=24000:cl=mono', '-t', String(GAP), gap]);
  const list = [];
  let t = 0, srt = '', idx = 0;
  sc.lines.forEach((line, i) => {
    const src = `${TMP}/tts/${sc.id}_${i}.mp3`;
    const wav = `${dir}/${sc.id}_${i}.wav`;
    run('ffmpeg', ['-y', '-v', 'error', '-i', src, '-ar', '24000', '-ac', '1', wav]);
    if (i > 0) { list.push(`file '${gap}'`); t += GAP; }
    list.push(`file '${wav}'`);
    const d = probe(wav);
    srt += `${++idx}\n${srtTime(t)} --> ${srtTime(t + d + 0.3)}\n${line}\n\n`;
    t += d;
  });
  const lst = `${dir}/${sc.id}.txt`;
  fs.writeFileSync(lst, list.join('\n'));
  const out = `${dir}/${sc.id}.wav`;
  run('ffmpeg', ['-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', lst, out]);
  fs.writeFileSync(`${TMP}/srt/${sc.id}.srt`, srt);
  return { audio: out, dur: t };
}

// ---------- 分段合成 ----------
const SUB_STYLE = "force_style='FontName=Noto Sans CJK SC,FontSize=17,PrimaryColour=&HFFFFFF&,OutlineColour=&H80000000&,BorderStyle=1,Outline=2,Shadow=1,MarginV=40'";
const VENC = ['-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p'];
const AENC = ['-c:a', 'aac', '-b:a', '128k', '-ar', '44100', '-ac', '2'];

// 点击推镜：点击前 0.5s 开始 0.35s 推近到 1.5×（点击点居中）→ 保持 → 点击后 1.2s 拉回。
// 推镜先于点击，保证「按钮语境→点击→后果」全程在镜内。
// z/x/y 均为 ffmpeg 表达式（zoompan 时间变量是 it，无 t）；窗口按时间排序后从最后一个
// 窗口向前折叠成 if 链——每个窗口只展开一次，窗口外/前回落到上一级链（zoom=1）。
function zoomExprs(wins) {
  const ws = wins.map(w => ({
    t0: +Math.max(0, w.t - 0.5).toFixed(2), t1: +(w.t - 0.15).toFixed(2),
    t2: +(w.t + 0.85).toFixed(2), t3: +(w.t + 1.2).toFixed(2),
    px: Math.min(1919, Math.max(1, w.x)), py: Math.min(1079, Math.max(1, w.y)),
  })).sort((a, b) => a.t0 - b.t0);
  let z = '1', x = '0', y = '0';
  for (const w of ws.slice().reverse()) {
    z = `if(lt(it,${w.t0}),${z},if(lt(it,${w.t1}),1+0.5*(it-${w.t0})/0.35,if(lt(it,${w.t2}),1.5,if(lt(it,${w.t3}),1.5-0.5*(it-${w.t2})/0.35,${z}))))`;
    x = `if(between(it,${w.t0},${w.t3}),max(0,min(iw-iw/zoom,${w.px}-iw/(2*zoom))),${x})`;
    y = `if(between(it,${w.t0},${w.t3}),max(0,min(ih-ih/zoom,${w.py}-ih/(2*zoom))),${y})`;
  }
  return { z, x, y };
}

function findWebm(id) {
  const d = `${TMP}/raw/${id}`;
  const f = fs.readdirSync(d).find(x => x.endsWith('.webm'));
  if (!f) throw new Error(`missing webm for ${id}`);
  return `${d}/${f}`;
}

function muxScene(sc) {
  fs.mkdirSync(`${TMP}/seg`, { recursive: true });
  const seg = `${TMP}/seg/${sc.id}.mp4`;
  if (sc.type === 'card') {
    const png = `${TMP}/cards/${sc.card || sc.id}.png`;
    const { audio, dur } = sceneAudio(sc);
    run('ffmpeg', ['-y', '-v', 'error', '-loop', '1', '-i', png, '-i', audio,
      '-filter_complex', '[0:v]scale=1920:1080,fps=30[v]',
      '-map', '[v]', '-map', '1:a', '-t', dur.toFixed(2), ...VENC, ...AENC, seg]);
    return seg;
  }
  // 幕间静音卡（无解说）
  if (sc.silent) {
    const png = `${TMP}/cards/${sc.card}.png`;
    run('ffmpeg', ['-y', '-v', 'error', '-loop', '1', '-i', png,
      '-f', 'lavfi', '-i', 'anullsrc=r=44100:cl=stereo',
      '-filter_complex', '[0:v]scale=1920:1080,fps=30[v]',
      '-map', '[v]', '-map', '1:a', '-t', '2.6', '-shortest', ...VENC, ...AENC, seg]);
    return seg;
  }
  // UI 场景：webm → 1080p30（+点击推镜）+ 字幕烧录 + tpad 补帧 / -t 裁齐到解说时长
  const webm = findWebm(sc.id);
  const { audio, dur } = sceneAudio(sc);
  const vDur = probe(webm);
  const pad = Math.max(0, dur + 1.2 - vDur); // +1.2s 收尾余量，防最后定格被裁
  const clicks = fs.existsSync(`${TMP}/clicks.json`)
    ? (JSON.parse(fs.readFileSync(`${TMP}/clicks.json`, 'utf8'))[sc.id] || [])
    : [];
  const head = clicks.length
    ? (() => { const e = zoomExprs(clicks);
        return `[0:v]scale=1920:1080,fps=30,zoompan=z='${e.z}':x='${e.x}':y='${e.y}':d=1:s=1920x1080:fps=30`; })()
    : '[0:v]scale=1920:1080,fps=30';
  const vf = head
    + (pad > 0.1 ? `,tpad=stop_mode=clone:stop_duration=${pad.toFixed(2)}` : '')
    + `,subtitles=${TMP}/srt/${sc.id}.srt:${SUB_STYLE}[v];[1:a]apad[a]`;
  run('ffmpeg', ['-y', '-v', 'error', '-i', webm, '-i', audio,
    '-filter_complex', vf, '-map', '[v]', '-map', '[a]',
    '-t', (dur + 1.2).toFixed(2), ...VENC, ...AENC, seg]);
  return seg;
}

// ---------- 成片串联：片头 → （过场卡+幕）×4 → 片尾 ----------
function mux() {
  const uiActs = narr.scenes.filter(s => s.type === 'ui');
  if (uiActs.length !== narr.cards.interludes.length) {
    throw new Error(`幕数(${uiActs.length})与过场卡数(${narr.cards.interludes.length})不一致`);
  }
  const order = [narr.scenes.find(s => s.id === 'intro')];
  uiActs.forEach((act, i) => {
    order.push({ id: `inter${i + 1}`, silent: true, card: `inter${i + 1}` });
    order.push(act);
  });
  order.push(narr.scenes.find(s => s.id === 'outro'));

  const segs = order.map(muxScene);
  fs.writeFileSync(`${TMP}/concat.txt`, segs.map(f => `file '${f}'`).join('\n'));

  const outName = process.argv[3] || 'WMS视频教程-销售全流程.mp4';
  const out = `${TMP}/${outName}`;
  run('ffmpeg', ['-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', `${TMP}/concat.txt`,
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '19', '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '128k', '-ar', '44100', '-ac', '2', out]);
  console.log('MUX_OK', out, `${probe(out).toFixed(1)}s`);
}

if (require.main === module) {
  const cmd = process.argv[2];
  if (cmd === 'tts') tts();
  else if (cmd === 'mux') mux();
  else { console.error('用法: node build.js tts|mux [输出文件名]'); process.exit(1); }
}
