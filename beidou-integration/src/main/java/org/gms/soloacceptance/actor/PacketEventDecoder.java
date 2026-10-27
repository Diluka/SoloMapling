package org.gms.soloacceptance.actor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Decodes only explicitly supported player-visible messages; raw packets never leave this call. */
final class PacketEventDecoder {
    static final int MAXIMUM_TEXT_CHARS = 1024;
    static final int SERVER_MESSAGE = 0x44;
    static final int CHAT_TEXT = 0xA2;
    static final int PLAYER_HINT = 0xD6;
    static final int NPC_TALK = 0x130;

    private PacketEventDecoder() { }

    static Map<String, Object> decode(byte[] bytes, Charset charset) {
        int opcode = (bytes[0] & 0xff) | (bytes[1] & 0xff) << 8;
        if (opcode != SERVER_MESSAGE && opcode != CHAT_TEXT && opcode != PLAYER_HINT && opcode != NPC_TALK) {
            return summary("unsupported", "unsupported_opcode");
        }
        try {
            Reader reader = new Reader(bytes, charset);
            Map<String, Object> fields = new LinkedHashMap<>();
            if (opcode == CHAT_TEXT) {
                fields.put("event_type", "chat_text");
                fields.put("sender_character_id", reader.integer());
                fields.put("gm", reader.bool());
                reader.text(fields);
                fields.put("show", reader.unsignedByte());
            } else if (opcode == PLAYER_HINT) {
                fields.put("event_type", "player_hint");
                reader.text(fields);
                fields.put("width", reader.unsignedShort());
                fields.put("height", reader.unsignedShort());
                fields.put("enabled", reader.bool());
                fields.put("clears_previous", ".".equals(fields.get("text")));
            } else if (opcode == NPC_TALK) {
                int speakerType = reader.unsignedByte();
                if (speakerType != 4) return summary("unsupported", "unsupported_npc_speaker_type");
                int npc = reader.integer();
                int type = reader.unsignedByte();
                if (type != 4) return summary("unsupported", "unsupported_npc_message_type");
                fields.put("event_type", "npc_dialogue");
                fields.put("speaker_type", speakerType);
                fields.put("npc_template_id", npc);
                fields.put("message_type", type);
                fields.put("speaker", reader.unsignedByte());
                reader.text(fields);
            } else {
                int type = reader.unsignedByte();
                if (type > 7) return summary("unsupported", "unsupported_server_message_type");
                fields.put("event_type", "server_message");
                fields.put("message_type", type);
                if (type == 4) fields.put("ticker_enabled", reader.bool());
                reader.text(fields);
                if (type == 3) {
                    fields.put("channel_id", reader.unsignedByte() + 1);
                    fields.put("mega_ear", reader.bool());
                } else if (type == 6) {
                    fields.put("trailing_value", reader.integer());
                } else if (type == 7) {
                    fields.put("npc_template_id", reader.integer());
                }
            }
            reader.requireEnd();
            fields.put("decode_status", "decoded");
            fields.put("information_level", "player_visible");
            return Collections.unmodifiableMap(fields);
        } catch (Malformed malformed) {
            return summary("malformed", malformed.reason);
        } catch (RuntimeException ignored) {
            return summary("malformed", "packet_decode_failed");
        }
    }

    static Map<String, Object> summary(String status, String reason) {
        return Map.of("event_type", "packet_summary", "decode_status", status, "reason", reason);
    }

    private static final class Reader {
        private final byte[] bytes;
        private final Charset charset;
        private int position = 2;

        Reader(byte[] bytes, Charset charset) {
            this.bytes = bytes;
            this.charset = charset;
        }

        int unsignedByte() {
            require(1, "packet_payload_incomplete");
            return bytes[position++] & 0xff;
        }

        boolean bool() {
            int value = unsignedByte();
            if (value > 1) throw malformed("packet_boolean_invalid");
            return value == 1;
        }

        int unsignedShort() {
            require(2, "packet_payload_incomplete");
            int value = (bytes[position] & 0xff) | (bytes[position + 1] & 0xff) << 8;
            position += 2;
            return value;
        }

        int integer() {
            require(4, "packet_payload_incomplete");
            int value = (bytes[position] & 0xff) | (bytes[position + 1] & 0xff) << 8
                    | (bytes[position + 2] & 0xff) << 16 | (bytes[position + 3] & 0xff) << 24;
            position += 4;
            return value;
        }

        void text(Map<String, Object> fields) {
            int length = unsignedShort();
            require(length, "packet_string_length_invalid");
            final String decoded;
            try {
                decoded = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, position, length)).toString();
            } catch (CharacterCodingException ignored) {
                throw malformed("packet_text_encoding_invalid");
            }
            position += length;
            boolean truncated = decoded.codePointCount(0, decoded.length()) > MAXIMUM_TEXT_CHARS;
            fields.put("text", truncated
                    ? decoded.substring(0, decoded.offsetByCodePoints(0, MAXIMUM_TEXT_CHARS)) : decoded);
            fields.put("text_truncated", truncated);
        }

        void requireEnd() {
            if (position != bytes.length) throw malformed("packet_trailing_bytes");
        }

        private void require(int count, String reason) {
            if (count > bytes.length - position) throw malformed(reason);
        }
    }

    private static Malformed malformed(String reason) { return new Malformed(reason); }

    private static final class Malformed extends RuntimeException {
        private final String reason;
        Malformed(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }
}
