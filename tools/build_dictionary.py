#!/usr/bin/env python3
"""Build the offline database from Simple English and compact English Wiktionary JSONL exports."""
import argparse
import gzip
import json
import shutil
import sqlite3
import tempfile
import unicodedata
from pathlib import Path


def normalize(word):
    return unicodedata.normalize("NFKC", word).lower().strip().replace("’", "'")


def add_extended(db, source):
    with source.open(encoding="utf-8") as entries:
        for line in entries:
            entry = json.loads(line)
            word = entry[""]
            key = normalize(word)
            parts = entry["p"]
            # The compact export does not associate individual senses with parts of speech.
            part = parts[0] if len(parts) == 1 else ""
            rows = [(key, word, part, definition, "", 1)
                    for definition in dict.fromkeys(entry["d"]) if definition.strip()]
            if rows:
                db.executemany("INSERT INTO senses VALUES (?, ?, ?, ?, ?, ?)", rows)
                db.executemany("INSERT OR IGNORE INTO forms VALUES (?, ?)", [
                    (normalize(form), key) for form in entry.get("f", []) if form and normalize(form) != key
                ])


def build_database(source, extended, target):
    with sqlite3.connect(target) as db:
        db.executescript("""
            CREATE TABLE senses (
                key TEXT NOT NULL, word TEXT NOT NULL, pos TEXT NOT NULL,
                definition TEXT NOT NULL, example TEXT NOT NULL, source INTEGER NOT NULL
            );
            CREATE TABLE forms (
                key TEXT NOT NULL, lemma TEXT NOT NULL, PRIMARY KEY (key, lemma)
            ) WITHOUT ROWID;
        """)
        with source.open(encoding="utf-8") as entries:
            for line in entries:
                entry = json.loads(line)
                if entry.get("lang_code") != "en":
                    continue
                word = entry["word"]
                key = normalize(word)
                pos = entry.get("pos", "")
                rows = []
                for sense in entry.get("senses", []):
                    if {"form-of", "alt-of"} & set(sense.get("tags", [])):
                        for base in sense.get("form_of", []) + sense.get("alt_of", []):
                            db.execute("INSERT OR IGNORE INTO forms VALUES (?, ?)",
                                       (key, normalize(base["word"])))
                        continue
                    glosses = sense.get("glosses", [])
                    if glosses and glosses[-1].strip():
                        examples = sense.get("examples", [])
                        example = examples[0].get("text", "") if examples else ""
                        rows.append((key, word, pos, glosses[-1], example, 0))
                if rows:
                    db.executemany("INSERT INTO senses VALUES (?, ?, ?, ?, ?, ?)", rows)
                    db.executemany("INSERT OR IGNORE INTO forms VALUES (?, ?)", [
                        (normalize(form["form"]), key) for form in entry.get("forms", [])
                        if form.get("form") and form["form"] != word
                    ])
        add_extended(db, extended)
        db.execute("CREATE INDEX senses_key ON senses (key, source)")
        db.execute("PRAGMA user_version = 2")


def build(source, extended, target):
    with tempfile.TemporaryDirectory(dir=target.parent) as directory:
        database = Path(directory) / "dictionary.db"
        compressed = database.with_suffix(".gz")
        build_database(source, extended, database)
        with database.open("rb") as stream, compressed.open("wb") as output:
            with gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0, compresslevel=6) as archive:
                shutil.copyfileobj(stream, archive)
        compressed.replace(target)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("extended", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    build(args.source, args.extended, args.target)
