import json
from ytmusicapi import YTMusic
api = YTMusic(language="en")
artists = [{"name":"חנן בן ארי","id":"UCz4N_ARAr1VxGzXq_Xj850g"},{"name":"ישי ריבו","id":"UCVYVQD6Qpuvo-Zg_jmjTrkw"},{"name":"Alex Clare","id":"UCZa7crFd_TP-vDn4fUdzqTw"},{"name":"Thank You Hashem","id":"UCHBST5NBv-gZeXkJjjP74Kw"},{"name":"המכביטס","id":"UCEg5tmfi6WmJZRnezYLVHSg"},{"name":"גד אלבז","id":"UCxEklyOKH3Jn1_1_AaRQsrQ"},{"name":"מרדכי שפירא","id":"UCm5x_womXL5E7TAYca5JkZQ"}]
def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values(): yield from walk(child)
    elif isinstance(value, list):
        for child in value: yield from walk(child)
def trim(value):
    if isinstance(value, dict):
        return {k: trim(v) for k, v in value.items() if k not in ("trackingParams", "clickTrackingParams", "frameworkUpdates", "menu", "accessibility", "responseContext")}
    if isinstance(value, list): return [trim(v) for v in value[:4]]
    return value
found = {}
for artist in artists:
    data = api._send_request("browse", {"browseId": artist["id"]})
    ids = {node["browseId"].removeprefix("VL") for node in walk(data) if str(node.get("browseId", "")).startswith("VL")}
    ids.update(node["playlistId"].removeprefix("VL") for node in walk(data) if node.get("playlistId"))
    print("ARTIST_PROBE", artist["name"], len(ids), sorted(ids)[:8], flush=True)
    for playlist in sorted(ids, key=lambda x: (not x.startswith("RDCLAK"), x))[:15]:
        if playlist in found or playlist.startswith("OLAK") or playlist.startswith("RDAM"):
            continue
        result = api._send_request("browse", {"browseId": "VL" + playlist})
        rows = [node["musicResponsiveListItemRenderer"] for node in walk(result) if "musicResponsiveListItemRenderer" in node]
        if not rows: continue
        audio = [row for row in rows if row.get("overlay", {}).get("musicItemThumbnailOverlayRenderer", {}).get("content", {}).get("musicPlayButtonRenderer", {}).get("playNavigationEndpoint", {}).get("watchEndpoint", {}).get("watchEndpointMusicSupportedConfigs", {}).get("watchEndpointMusicConfig", {}).get("musicVideoType") == "MUSIC_VIDEO_TYPE_ATV"]
        print("AUDIO_PROBE", playlist, len(rows), "audio", len(audio), flush=True)
        if not audio: continue
        found[playlist] = True
        print("PROBE_ROWS", playlist, len(rows), "missing_edit", sum(not row.get("playlistItemData", {}).get("playlistSetVideoId") for row in rows), flush=True)
        print("PLAYLIST_JSON " + json.dumps({"id": playlist, "data": trim(result)}, ensure_ascii=False), flush=True)
        break
    if len(found) >= 2: break
if not found: raise RuntimeError("No representative public playlist found")
