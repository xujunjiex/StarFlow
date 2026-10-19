CREATE TABLE imported_page_translation (
    mangaId INTEGER NOT NULL,
    pageIndex INTEGER NOT NULL,
    state INTEGER NOT NULL,
    sourceText TEXT,
    translatedText TEXT,
    bubbleRects TEXT,
    failCode TEXT,
    failMessage TEXT,
    updatedAtMs INTEGER NOT NULL,
    mangaKey TEXT,
    translatorName TEXT,
    sourceLang TEXT,
    targetLang TEXT,
    PRIMARY KEY(mangaId, pageIndex)
);
CREATE TABLE translation_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    type INTEGER NOT NULL,
    sourceText TEXT,
    translatedText TEXT,
    imagePath TEXT,
    thumbnailPath TEXT,
    sourceLang TEXT NOT NULL,
    targetLang TEXT NOT NULL,
    translatorName TEXT NOT NULL,
    pHash INTEGER NOT NULL,
    pHash2 INTEGER NOT NULL,
    pHash3 INTEGER NOT NULL,
    pHash4 INTEGER NOT NULL,
    created_at INTEGER NOT NULL DEFAULT 0,
    session_id TEXT NOT NULL DEFAULT '',
    last_session_id TEXT NOT NULL DEFAULT '',
    original_image_path TEXT,
    is_retranslated INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL DEFAULT 0,
    bubble_rects TEXT
);
CREATE INDEX index_translation_history_type_created_at ON translation_history (type, created_at);
CREATE TABLE page_cache (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    historyId INTEGER NOT NULL,
    pHash INTEGER NOT NULL,
    pHash2 INTEGER NOT NULL,
    pHash3 INTEGER NOT NULL,
    pHash4 INTEGER NOT NULL,
    mode INTEGER NOT NULL,
    lastAccessedAt INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    cropWidth INTEGER NOT NULL,
    cropHeight INTEGER NOT NULL,
    crop_left INTEGER NOT NULL DEFAULT 0,
    crop_top INTEGER NOT NULL DEFAULT 0,
    crop_right INTEGER NOT NULL DEFAULT 0,
    crop_bottom INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(historyId) REFERENCES translation_history(id) ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE INDEX index_page_cache_historyId ON page_cache (historyId);
CREATE INDEX index_page_cache_pHash_mode ON page_cache (pHash, mode);
CREATE INDEX index_page_cache_lastAccessedAt ON page_cache (lastAccessedAt);
CREATE TABLE text_translate_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    original_text TEXT NOT NULL,
    translated_text TEXT NOT NULL,
    source_lang TEXT NOT NULL,
    target_lang TEXT NOT NULL,
    engine_name TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE chat_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    role INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
