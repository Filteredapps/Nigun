import io, json, zipfile
from pathlib import Path
archive = b"".join(p.read_bytes() for p in sorted(Path(".").glob("nigun-overlay.zip.part-*")))
with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
    for path in ["app/src/main/kotlin/com/metrolist/music/utils/RemoteHomeFeedUpdater.kt", "innertube/src/main/kotlin/com/metrolist/innertube/models/MusicResponsiveListItemRenderer.kt"]:
        if path in bundle.namelist():
            print("SOURCE_JSON " + json.dumps({"path": path, "content": bundle.read(path).decode()}), flush=True)
from ytmusicapi import YTMusic
api = YTMusic(language="en")
def trim(value):
    if isinstance(value, dict):
        return {k: trim(v) for k, v in value.items() if k not in ("trackingParams", "clickTrackingParams", "frameworkUpdates", "responseContext")}
    if isinstance(value, list):
        return [trim(v) for v in value[:3]]
    return value
for result in api.search("Yaakov Shwekey", filter="playlists", limit=3)[:3]:
    playlist = result.get("playlistId")
    if not playlist:
        continue
    data = api._send_request("browse", {"browseId": "VL" + playlist.removeprefix("VL")})
    print("PLAYLIST_JSON " + json.dumps({"id": playlist, "data": trim(data)}, ensure_ascii=False), flush=True)
