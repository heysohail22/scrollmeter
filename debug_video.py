#!/usr/bin/env python3
import os
import sys
import glob
import json
from PIL import Image

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
FRAMES_DIR = os.path.join(SCRIPT_DIR, "recordings", "extracted_1fps")
HTML_OUT = os.path.join(SCRIPT_DIR, "debug_dashboard.html")

def analyze_frame(img):
    w, h = img.size
    lum = list(img.convert("L").getdata())

    # 1. Right Action Column (X: 84%..98%, Y: 45%..85%)
    x1, x2 = int(w * 0.84), int(w * 0.98)
    y1, y2 = int(h * 0.45), int(h * 0.85)
    edge_sum = 0
    bright_pixels = 0
    pixel_count = 0
    for y in range(y1, y2, 2):
        row = y * w
        for x in range(x1, x2 - 1):
            p1 = lum[row + x]
            p2 = lum[row + x + 1]
            edge_sum += abs(p1 - p2)
            if p1 > 160:
                bright_pixels += 1
            pixel_count += 1
    avg_edge = edge_sum / pixel_count if pixel_count > 0 else 0
    bright_ratio = bright_pixels / pixel_count if pixel_count > 0 else 0
    has_action_column = avg_edge >= 7.0

    # 2. Bottom Nav (Y: 93%..99%)
    nav_y1, nav_y2 = int(h * 0.93), int(h * 0.99)
    def nav_avg(rx1, rx2):
        tot, cnt = 0, 0
        for y in range(nav_y1, nav_y2):
            row = y * w
            for x in range(int(w * rx1), int(w * rx2)):
                tot += lum[row + x]
                cnt += 1
        return tot / cnt if cnt > 0 else 0

    home_val = nav_avg(0.04, 0.16)
    reels_val = nav_avg(0.24, 0.36)
    has_nav = home_val > 25 and reels_val > 25
    is_home_nav = has_nav and (home_val > reels_val * 1.15)
    is_reels_nav = has_nav and (reels_val > home_val * 1.25)

    # 3. Top Header: Back arrow & Posts feed check
    top_y1, top_y2 = int(h * 0.03), int(h * 0.08)
    back_arrow_pts = sum(1 for y in range(top_y1, top_y2) for x in range(int(w * 0.03), int(w * 0.14)) if lum[y * w + x] > 180)
    has_back_arrow = (back_arrow_pts / (int(h * 0.05) * int(w * 0.11))) > 0.04

    posts_pts = sum(1 for y in range(top_y1, top_y2) for x in range(int(w * 0.18), int(w * 0.40)) if lum[y * w + x] > 180)
    has_posts_title = has_back_arrow and (posts_pts / (int(h * 0.05) * int(w * 0.22))) > 0.04

    # 4. Modal Sheet: Drag handle
    has_modal_handle = False
    for y in range(int(h * 0.25), int(h * 0.45)):
        c_val = sum(lum[y * w + x] for x in range(int(w * 0.46), int(w * 0.54))) / int(w * 0.08)
        if c_val > 100:
            l_val = sum(lum[y * w + x] for x in range(int(w * 0.35), int(w * 0.42))) / int(w * 0.07)
            r_val = sum(lum[y * w + x] for x in range(int(w * 0.58), int(w * 0.65))) / int(w * 0.07)
            if c_val > l_val * 2.0 and c_val > r_val * 2.0:
                has_modal_handle = True
                break

    # 5. UI Anchor region (X: 5%..75%, Y: 78%..92%)
    anchor_y1, anchor_y2 = int(h * 0.78), int(h * 0.92)
    anchor_x1, anchor_x2 = int(w * 0.05), int(w * 0.75)
    anchor_lum = [lum[y * w + x] for y in range(anchor_y1, anchor_y2) for x in range(anchor_x1, anchor_x2)]

    # Decision
    if is_home_nav:
        context = "INSTAGRAM_HOME"
        reason = "Home tab icon active in bottom navigation"
    elif has_posts_title:
        context = "INSTAGRAM_POST"
        reason = "Profile posts feed ('<- Posts') header detected"
    elif has_modal_handle:
        context = "REELS_MODAL_OPEN"
        reason = "Comments / Share modal drag-handle detected"
    elif has_action_column:
        if is_reels_nav:
            context = "REELS_ACTIVE"
            reason = "Reels tab active + Right action column verified"
        elif not has_nav and has_back_arrow:
            context = "REELS_ACTIVE"
            reason = "Modal Reel opened from Explore ('<- Explore') with action column"
        elif not has_nav:
            context = "REELS_ACTIVE"
            reason = "Full-bleed Reel with right action column"
        else:
            context = "REELS_NOT_ACTIVE"
            reason = "Action icons present but non-reels navigation active"
    else:
        context = "REELS_NOT_ACTIVE"
        reason = "No Reels action column or outside Reels"

    return {
        "context": context,
        "reason": reason,
        "action_edge": round(avg_edge, 1),
        "bright_ratio": round(bright_ratio, 2),
        "home_nav": round(home_val, 1),
        "reels_nav": round(reels_val, 1),
        "has_modal": has_modal_handle,
        "has_back_arrow": has_back_arrow,
        "anchor_data": anchor_lum
    }

def main():
    frame_files = sorted(glob.glob(os.path.join(FRAMES_DIR, "*.jpg")))
    if not frame_files:
        print(f"Error: No frames found in {FRAMES_DIR}")
        sys.exit(1)

    print(f"==================================================")
    print(f" ScrollMeter Offline Video Debugger (330 Frames)")
    print(f"==================================================")

    results = []
    reel_count = 0
    prev_anchor = None
    prev_context = None

    for i, fpath in enumerate(frame_files):
        sec = i + 1
        img = Image.open(fpath)
        data = analyze_frame(img)

        # Transition tracking
        transition_event = "NONE"
        if data["context"] == "REELS_ACTIVE":
            if prev_context != "REELS_ACTIVE":
                # Entered Reels or first reel
                if reel_count == 0:
                    reel_count = 1
                    transition_event = f"INITIAL_REEL (#{reel_count})"
            else:
                # Compare anchor with previous second
                if prev_anchor is not None:
                    curr_anchor = data["anchor_data"]
                    diff = sum(abs(a - b) for a, b in zip(curr_anchor, prev_anchor)) / len(curr_anchor)
                    if diff > 15.0:
                        reel_count += 1
                        transition_event = f"VERIFIED_TRANSITION (#{reel_count}, diff={diff:.1f})"
                    elif diff > 5.0 and diff <= 15.0:
                        transition_event = f"IN_REEL_CUT_IGNORED (diff={diff:.1f})"

            prev_anchor = data["anchor_data"]
        else:
            prev_anchor = None

        prev_context = data["context"]

        results.append({
            "second": sec,
            "filename": os.path.basename(fpath),
            "context": data["context"],
            "reason": data["reason"],
            "action_edge": data["action_edge"],
            "home_nav": data["home_nav"],
            "reels_nav": data["reels_nav"],
            "has_modal": data["has_modal"],
            "event": transition_event,
            "reel_count": reel_count
        })

    # Summary table
    print("\n--- Key Timeline Highlights ---")
    for r in results:
        if r["event"] != "NONE" or r["second"] in [5, 20, 60, 155, 205, 226, 275, 300]:
            print(f"[{r['second']:03d}s] {r['context']:18s} | Count: {r['reel_count']} | Event: {r['event']:30s} | {r['reason']}")

    # Generate interactive HTML dashboard
    generate_html(results)
    print(f"\n Visual HTML Dashboard generated at:\n file://{HTML_OUT}")
    print(f"\nYou can open this file in VS Code or your browser to scrub through the video and inspect every frame!")

def generate_html(results):
    results_json = json.dumps(results)
    html_content = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>ScrollMeter Offline Video Debugger</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0F172A; color: #F8FAFC; margin: 0; padding: 20px; }}
        .header {{ display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #334155; padding-bottom: 12px; margin-bottom: 20px; }}
        .badge {{ padding: 4px 10px; border-radius: 999px; font-size: 13px; font-weight: bold; }}
        .badge-active {{ background: #10B981; color: #022C22; }}
        .badge-home {{ background: #3B82F6; color: #1E3A8A; }}
        .badge-modal {{ background: #F59E0B; color: #78350F; }}
        .badge-not {{ background: #64748B; color: #0F172A; }}
        .layout {{ display: grid; grid-template-columns: 360px 1fr; gap: 24px; }}
        .preview-box {{ position: relative; width: 360px; height: 800px; background: #000; border-radius: 16px; overflow: hidden; border: 2px solid #334155; box-shadow: 0 10px 30px rgba(0,0,0,0.5); }}
        .preview-img {{ width: 100%; height: 100%; object-fit: contain; }}
        .box-overlay {{ position: absolute; border: 2px dashed; pointer-events: none; }}
        .box-action {{ right: 5%; top: 45%; width: 14%; height: 40%; border-color: #EC4899; background: rgba(236, 72, 153, 0.15); }}
        .box-nav {{ left: 0; bottom: 0; width: 100%; height: 7%; border-color: #38BDF8; background: rgba(56, 189, 248, 0.15); }}
        .box-anchor {{ left: 5%; bottom: 8%; width: 70%; height: 14%; border-color: #A855F7; background: rgba(168, 85, 247, 0.15); }}
        .box-modal {{ left: 35%; top: 25%; width: 30%; height: 20%; border-color: #FBBF24; background: rgba(251, 191, 36, 0.15); }}
        .card {{ background: #1E293B; border-radius: 12px; padding: 20px; border: 1px solid #334155; margin-bottom: 16px; }}
        .scrubber-bar {{ width: 100%; margin: 15px 0; }}
        .timeline-btn-group {{ display: flex; flex-wrap: wrap; gap: 6px; max-height: 280px; overflow-y: auto; padding: 4px; }}
        .t-btn {{ background: #334155; color: #CBD5E1; border: none; padding: 6px 10px; border-radius: 6px; cursor: pointer; font-size: 11px; }}
        .t-btn:hover {{ background: #475569; }}
        .t-btn.active {{ background: #8B5CF6; color: white; font-weight: bold; }}
        .t-btn.reels {{ border-left: 3px solid #10B981; }}
        .t-btn.home {{ border-left: 3px solid #3B82F6; }}
        .t-btn.modal {{ border-left: 3px solid #F59E0B; }}
        .t-btn.trans {{ background: #059669; color: white; font-weight: bold; }}
        .metric-grid {{ display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-top: 12px; }}
        .metric-tile {{ background: #0F172A; padding: 12px; border-radius: 8px; border: 1px solid #334155; }}
        .metric-label {{ font-size: 11px; color: #94A3B8; text-transform: uppercase; }}
        .metric-val {{ font-size: 20px; font-weight: bold; color: #38BDF8; margin-top: 4px; }}
    </style>
</head>
<body>
    <div class="header">
        <div>
            <h2 style="margin: 0;">ScrollMeter Video Debugger</h2>
            <div style="font-size: 13px; color: #94A3B8; margin-top: 4px;">Dataset: <code>instagram_test_recording.mp4</code> (330 Seconds)</div>
        </div>
        <div>
            <span style="font-size: 14px; margin-right: 12px;">Verified Reels Count: <strong id="verifiedCount" style="color: #10B981; font-size: 22px;">0</strong></span>
            <span id="contextBadge" class="badge badge-not">INIT</span>
        </div>
    </div>

    <div class="layout">
        <!-- Frame Preview with ROI Bounding Boxes -->
        <div>
            <div class="preview-box">
                <img id="frameImg" class="preview-img" src="" alt="Frame preview">
                <div class="box-overlay box-action" title="Right Action Column (X: 84%..98%, Y: 45%..85%)"></div>
                <div class="box-overlay box-nav" title="Bottom Navigation Bar (Y: 93%..99%)"></div>
                <div class="box-overlay box-anchor" title="Creator UI Anchor Box (X: 5%..75%, Y: 78%..92%)"></div>
                <div class="box-overlay box-modal" title="Modal Drag Handle Zone (Y: 25%..45%)"></div>
            </div>
            <div style="display: flex; justify-content: space-between; margin-top: 10px;">
                <button onclick="prevSec()" class="t-btn" style="padding: 8px 16px;">◀ Prev (1s)</button>
                <span id="timeLabel" style="font-weight: bold; font-size: 16px;">t = 001s / 330s</span>
                <button onclick="nextSec()" class="t-btn" style="padding: 8px 16px;">Next (1s) ▶</button>
            </div>
        </div>

        <!-- Telemetry & Timeline Scrubber -->
        <div>
            <!-- Timeline Scrubber -->
            <div class="card">
                <div style="display: flex; justify-content: space-between;">
                    <strong>Scrub Video Timeline</strong>
                    <span id="sliderVal">001s</span>
                </div>
                <input type="range" id="timeSlider" class="scrubber-bar" min="1" max="330" value="1" oninput="jumpToSec(parseInt(this.value))">

                <div style="margin-top: 10px; font-size: 12px; color: #94A3B8;">Jump to Known Events:</div>
                <div style="display: flex; gap: 8px; margin-top: 6px; flex-wrap: wrap;">
                    <button class="t-btn" onclick="jumpToSec(5)">Home Feed (t=005s)</button>
                    <button class="t-btn reels" onclick="jumpToSec(25)">Reel 1 (t=025s)</button>
                    <button class="t-btn trans" onclick="jumpToSec(36)">Swipe Reel 2 (t=036s)</button>
                    <button class="t-btn" onclick="jumpToSec(45)">In-Reel Cut (t=045s)</button>
                    <button class="t-btn modal" onclick="jumpToSec(60)">Comments Sheet (t=060s)</button>
                    <button class="t-btn trans" onclick="jumpToSec(80)">Reel 3 (t=080s)</button>
                    <button class="t-btn modal" onclick="jumpToSec(155)">Share Sheet (t=155s)</button>
                    <button class="t-btn" onclick="jumpToSec(205)">DM Chat (t=205s)</button>
                    <button class="t-btn reels" onclick="jumpToSec(226)">Modal Explore Reel (t=226s)</button>
                    <button class="t-btn" onclick="jumpToSec(275)">Profile (t=275s)</button>
                    <button class="t-btn" onclick="jumpToSec(300)">Profile Post Feed (t=300s)</button>
                </div>
            </div>

            <!-- Real-Time Metrics for Current Second -->
            <div class="card">
                <h3 style="margin-top: 0; margin-bottom: 6px;">Detector Telemetry</h3>
                <div id="reasonText" style="color: #94A3B8; font-size: 14px; margin-bottom: 12px;">-</div>

                <div class="metric-grid">
                    <div class="metric-tile">
                        <div class="metric-label">Right Action Edge</div>
                        <div class="metric-val" id="valActionEdge">0.0</div>
                    </div>
                    <div class="metric-tile">
                        <div class="metric-label">Home Nav Lum</div>
                        <div class="metric-val" id="valHomeNav">0.0</div>
                    </div>
                    <div class="metric-tile">
                        <div class="metric-label">Reels Nav Lum</div>
                        <div class="metric-val" id="valReelsNav">0.0</div>
                    </div>
                    <div class="metric-tile">
                        <div class="metric-label">Event</div>
                        <div class="metric-val" id="valEvent" style="font-size: 13px; color: #10B981;">NONE</div>
                    </div>
                </div>
            </div>

            <!-- Full Second-by-Second Button Grid -->
            <div class="card">
                <div style="margin-bottom: 8px; font-weight: bold;">All 330 Seconds (Click to jump):</div>
                <div class="timeline-btn-group" id="btnGroup"></div>
            </div>
        </div>
    </div>

    <script>
        const data = {results_json};
        let currentIdx = 0;

        function renderSec(idx) {{
            currentIdx = idx;
            const r = data[idx];
            document.getElementById('frameImg').src = 'recordings/extracted_1fps/' + r.filename;
            document.getElementById('timeLabel').innerText = `t = ${{String(r.second).padStart(3, '0')}}s / 330s`;
            document.getElementById('sliderVal').innerText = `${{String(r.second).padStart(3, '0')}}s`;
            document.getElementById('timeSlider').value = r.second;

            // Badges
            const badge = document.getElementById('contextBadge');
            badge.innerText = r.context;
            badge.className = 'badge ' + (
                r.context === 'REELS_ACTIVE' ? 'badge-active' :
                r.context === 'INSTAGRAM_HOME' ? 'badge-home' :
                r.context === 'REELS_MODAL_OPEN' ? 'badge-modal' : 'badge-not'
            );

            document.getElementById('verifiedCount').innerText = r.reel_count;
            document.getElementById('reasonText').innerText = r.reason;
            document.getElementById('valActionEdge').innerText = r.action_edge;
            document.getElementById('valHomeNav').innerText = r.home_nav;
            document.getElementById('valReelsNav').innerText = r.reels_nav;
            document.getElementById('valEvent').innerText = r.event;

            // Highlight active button
            document.querySelectorAll('.t-btn').forEach((b, i) => {{
                if (b.dataset.sec == r.second) b.classList.add('active');
                else b.classList.remove('active');
            }});
        }}

        function jumpToSec(sec) {{
            renderSec(sec - 1);
        }}

        function prevSec() {{
            if (currentIdx > 0) renderSec(currentIdx - 1);
        }}

        function nextSec() {{
            if (currentIdx < data.length - 1) renderSec(currentIdx + 1);
        }}

        // Build button list
        const group = document.getElementById('btnGroup');
        data.forEach(r => {{
            const btn = document.createElement('button');
            btn.className = 't-btn ' + (
                r.event.includes('TRANSITION') ? 'trans' :
                r.context === 'REELS_ACTIVE' ? 'reels' :
                r.context === 'INSTAGRAM_HOME' ? 'home' :
                r.context === 'REELS_MODAL_OPEN' ? 'modal' : ''
            );
            btn.dataset.sec = r.second;
            btn.innerText = `${{r.second}}s`;
            btn.onclick = () => renderSec(r.second - 1);
            group.appendChild(btn);
        }});

        // Keyboard arrows
        window.addEventListener('keydown', e => {{
            if (e.key === 'ArrowLeft') prevSec();
            if (e.key === 'ArrowRight') nextSec();
        }});

        // Initial render
        renderSec(0);
    </script>
</body>
</html>
"""
    with open(HTML_OUT, "w") as f:
        f.write(html_content)

if __name__ == "__main__":
    main()
