from pathlib import Path

from rc.config import load_config


def test_defaults_when_no_file(tmp_path):
    cfg = load_config(tmp_path / "absent.toml")
    assert cfg.port == 8765
    assert cfg.scan_roots == [Path("/media"), Path("/mnt"), Path("/run/media")]


def test_toml_overrides(tmp_path):
    f = tmp_path / "c.toml"
    f.write_text('port = 9000\ntmdb_api_key = "k"\n')
    cfg = load_config(f)
    assert cfg.port == 9000
    assert cfg.tmdb_api_key == "k"


def test_rc_config_env(tmp_path, monkeypatch):
    f = tmp_path / "env.toml"
    f.write_text("port = 9111\n")
    monkeypatch.setenv("RC_CONFIG", str(f))
    assert load_config(None).port == 9111
