-- MoChat Phase 1: Add multimedia message support (RustFS integration)

-- 1. 在 messages 表中增加多媒体相关字段
ALTER TABLE messages ADD COLUMN IF NOT EXISTS message_type TEXT NOT NULL DEFAULT 'text';
ALTER TABLE messages ADD COLUMN IF NOT EXISTS media_url TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS thumbnail_url TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS mime_type TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS file_name TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS duration INTEGER;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS width INTEGER;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS height INTEGER;

-- 2. 添加约束
ALTER TABLE messages DROP CONSTRAINT IF EXISTS messages_type_chk;
ALTER TABLE messages ADD CONSTRAINT messages_type_chk
    CHECK (message_type IN ('text', 'image', 'video', 'audio', 'file'));

-- 当 message_type = 'text' 时，多媒体字段应为 NULL
ALTER TABLE messages DROP CONSTRAINT IF EXISTS messages_text_media_consistency_chk;
ALTER TABLE messages ADD CONSTRAINT messages_text_media_consistency_chk
    CHECK (
        (message_type = 'text' AND media_url IS NULL) OR
        (message_type != 'text' AND media_url IS NOT NULL)
        );

-- 3. 创建索引优化查询
CREATE INDEX IF NOT EXISTS messages_type_idx ON messages (message_type);
CREATE INDEX IF NOT EXISTS messages_media_url_idx ON messages (media_url) WHERE media_url IS NOT NULL;
