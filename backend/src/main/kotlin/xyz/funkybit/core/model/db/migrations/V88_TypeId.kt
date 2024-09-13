package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V88_TypeId : Migration() {

    override fun run() {
        // https://github.com/jetify-com/typeid-sql

        transaction {
            exec(
                """
                -- Function to generate new v7 UUIDs.
                -- In the future we might want use an extension: https://github.com/fboulnois/pg_uuidv7
                -- Or, once the UUIDv7 spec is finalized, it will probably make it into the 'uuid-ossp' extension
                -- and a custom function will no longer be necessary.
                create or replace function uuid_generate_v7()
                returns uuid
                as ${'$'}${'$'}
                declare
                  unix_ts_ms bytea;
                  uuid_bytes bytea;
                begin
                  unix_ts_ms = substring(int8send(floor(extract(epoch from clock_timestamp()) * 1000)::bigint) from 3);
                  uuid_bytes = uuid_send(gen_random_uuid());
                  uuid_bytes = overlay(uuid_bytes placing unix_ts_ms from 1 for 6);
                  uuid_bytes = set_byte(uuid_bytes, 6, (b'0111' || get_byte(uuid_bytes, 6)::bit(4))::bit(8)::int);
                  return encode(uuid_bytes, 'hex')::uuid;
                end
                ${'$'}${'$'}
                language plpgsql
                volatile;
                """.trimIndent(),
            )
        }

        transaction {
            exec(
                """
                -- Functions to encode and decode UUIDs to and from base32.
                
                -- Encodes a UUID as a base32 string
                create or replace function base32_encode(id uuid)
                returns text
                as ${'$'}${'$'}
                declare
                  bytes bytea;
                  alphabet bytea = '0123456789abcdefghjkmnpqrstvwxyz';
                  output text = '';
                begin
                  bytes = uuid_send(id);
                
                  -- 10 byte timestamp
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 0) & 224) >> 5));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 0) & 31)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 1) & 248) >> 3));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 1) & 7) << 2) | ((get_byte(bytes, 2) & 192) >> 6)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 2) & 62) >> 1));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 2) & 1) << 4) | ((get_byte(bytes, 3) & 240) >> 4)));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 3) & 15) << 1) | ((get_byte(bytes, 4) & 128) >> 7)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 4) & 124) >> 2));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 4) & 3) << 3) | ((get_byte(bytes, 5) & 224) >> 5)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 5) & 31)));
                
                  -- 16 bytes of entropy
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 6) & 248) >> 3));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 6) & 7) << 2) | ((get_byte(bytes, 7) & 192) >> 6)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 7) & 62) >> 1));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 7) & 1) << 4) | ((get_byte(bytes, 8) & 240) >> 4)));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 8) & 15) << 1) | ((get_byte(bytes, 9) & 128) >> 7)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 9) & 124) >> 2));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 9) & 3) << 3) | ((get_byte(bytes, 10) & 224) >> 5)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 10) & 31)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 11) & 248) >> 3));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 11) & 7) << 2) | ((get_byte(bytes, 12) & 192) >> 6)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 12) & 62) >> 1));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 12) & 1) << 4) | ((get_byte(bytes, 13) & 240) >> 4)));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 13) & 15) << 1) | ((get_byte(bytes, 14) & 128) >> 7)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 14) & 124) >> 2));
                  output = output || chr(get_byte(alphabet, ((get_byte(bytes, 14) & 3) << 3) | ((get_byte(bytes, 15) & 224) >> 5)));
                  output = output || chr(get_byte(alphabet, (get_byte(bytes, 15) & 31)));
                
                  return output;
                end
                ${'$'}${'$'}
                language plpgsql
                immutable;
                
                
                create or replace function base32_decode(s text) 
                returns uuid as ${'$'}${'$'}
                declare
                  dec bytea = '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF 00 01'::bytea ||
                              '\x02 03 04 05 06 07 08 09 FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF 0A 0B 0C'::bytea ||
                              '\x0D 0E 0F 10 11 FF 12 13 FF 14'::bytea ||
                              '\x15 FF 16 17 18 19 1A FF 1B 1C'::bytea ||
                              '\x1D 1E 1F FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF FF FF FF FF'::bytea ||
                              '\xFF FF FF FF FF FF'::bytea;
                  v bytea = convert_to(s, 'UTF8');            
                  id bytea = '\x00000000000000000000000000000000';
                begin
                  if length(s) <> 26 then
                    raise exception 'typeid suffix must be 26 characters';
                  end if;
                
                  if get_byte(dec, get_byte(v, 0)) = 255 or
                     get_byte(dec, get_byte(v, 1)) = 255 or
                     get_byte(dec, get_byte(v, 2)) = 255 or
                     get_byte(dec, get_byte(v, 3)) = 255 or
                     get_byte(dec, get_byte(v, 4)) = 255 or
                     get_byte(dec, get_byte(v, 5)) = 255 or
                     get_byte(dec, get_byte(v, 6)) = 255 or
                     get_byte(dec, get_byte(v, 7)) = 255 or
                     get_byte(dec, get_byte(v, 8)) = 255 or
                     get_byte(dec, get_byte(v, 9)) = 255 or
                     get_byte(dec, get_byte(v, 10)) = 255 or
                     get_byte(dec, get_byte(v, 11)) = 255 or
                     get_byte(dec, get_byte(v, 12)) = 255 or
                     get_byte(dec, get_byte(v, 13)) = 255 or
                     get_byte(dec, get_byte(v, 14)) = 255 or
                     get_byte(dec, get_byte(v, 15)) = 255 or
                     get_byte(dec, get_byte(v, 16)) = 255 or
                     get_byte(dec, get_byte(v, 17)) = 255 or
                     get_byte(dec, get_byte(v, 18)) = 255 or
                     get_byte(dec, get_byte(v, 19)) = 255 or
                     get_byte(dec, get_byte(v, 20)) = 255 or
                     get_byte(dec, get_byte(v, 21)) = 255 or
                     get_byte(dec, get_byte(v, 22)) = 255 or
                     get_byte(dec, get_byte(v, 23)) = 255 or
                     get_byte(dec, get_byte(v, 24)) = 255 or
                     get_byte(dec, get_byte(v, 25)) = 255
                  then
                    raise exception 'typeid suffix must only use characters from the base32 alphabet';
                  end if;
                  
                  if chr(get_byte(v, 0)) > '7' then
                    raise exception 'typeid suffix must start with 0-7';
                  end if;
                  -- Transform base32 to binary array
                  -- 6 bytes timestamp (48 bits)
                  id = set_byte(id, 0, (get_byte(dec, get_byte(v, 0)) << 5) | get_byte(dec, get_byte(v, 1)));
                  id = set_byte(id, 1, (get_byte(dec, get_byte(v, 2)) << 3) | (get_byte(dec, get_byte(v, 3)) >> 2));
                  id = set_byte(id, 2, ((get_byte(dec, get_byte(v, 3)) & 3) << 6) | (get_byte(dec, get_byte(v, 4)) << 1) | (get_byte(dec, get_byte(v, 5)) >> 4));
                  id = set_byte(id, 3, ((get_byte(dec, get_byte(v, 5)) & 15) << 4) | (get_byte(dec, get_byte(v, 6)) >> 1));
                  id = set_byte(id, 4, ((get_byte(dec, get_byte(v, 6)) & 1) << 7) | (get_byte(dec, get_byte(v, 7)) << 2) | (get_byte(dec, get_byte(v, 8)) >> 3));
                  id = set_byte(id, 5, ((get_byte(dec, get_byte(v, 8)) & 7) << 5) | get_byte(dec, get_byte(v, 9)));
                
                  -- 10 bytes of entropy (80 bits)
                  id = set_byte(id, 6, (get_byte(dec, get_byte(v, 10)) << 3) | (get_byte(dec, get_byte(v, 11)) >> 2));
                  id = set_byte(id, 7, ((get_byte(dec, get_byte(v, 11)) & 3) << 6) | (get_byte(dec, get_byte(v, 12)) << 1) | (get_byte(dec, get_byte(v, 13)) >> 4));
                  id = set_byte(id, 8, ((get_byte(dec, get_byte(v, 13)) & 15) << 4) | (get_byte(dec, get_byte(v, 14)) >> 1));
                  id = set_byte(id, 9, ((get_byte(dec, get_byte(v, 14)) & 1) << 7) | (get_byte(dec, get_byte(v, 15)) << 2) | (get_byte(dec, get_byte(v, 16)) >> 3));
                  id = set_byte(id, 10, ((get_byte(dec, get_byte(v, 16)) & 7) << 5) | get_byte(dec, get_byte(v, 17)));
                  id = set_byte(id, 11, (get_byte(dec, get_byte(v, 18)) << 3) | (get_byte(dec, get_byte(v, 19)) >> 2));
                  id = set_byte(id, 12, ((get_byte(dec, get_byte(v, 19)) & 3) << 6) | (get_byte(dec, get_byte(v, 20)) << 1) | (get_byte(dec, get_byte(v, 21)) >> 4));
                  id = set_byte(id, 13, ((get_byte(dec, get_byte(v, 21)) & 15) << 4) | (get_byte(dec, get_byte(v, 22)) >> 1));
                  id = set_byte(id, 14, ((get_byte(dec, get_byte(v, 22)) & 1) << 7) | (get_byte(dec, get_byte(v, 23)) << 2) | (get_byte(dec, get_byte(v, 24)) >> 3));
                  id = set_byte(id, 15, ((get_byte(dec, get_byte(v, 24)) & 7) << 5) | get_byte(dec, get_byte(v, 25)));
                  return encode(id, 'hex')::uuid;
                end
                ${'$'}${'$'}
                language plpgsql
                immutable;
                """.trimIndent(),
            )
        }

        transaction {
            exec(
                """
                -- Implementation of typeids in SQL (Postgres).
                -- This file:
                -- + Defines a `typeid` type: a composite type consisting of a type prefix,
                --   and a UUID
                -- + Defines functions to generate and validate typeids in SQL.
                
                -- Create a `typeid` type.
                create type "typeid" as ("type" varchar(63), "uuid" uuid);
                
                -- Function that generates a random typeid of the given type.
                -- This depends on the `uuid_generate_v7` function defined in `uuid_v7.sql`.
                create or replace function typeid_generate(prefix text)
                returns typeid
                as ${'$'}${'$'}
                begin
                  if (prefix is null) or not (prefix ~ '^[a-z]{0,63}${'$'}') then
                    raise exception 'typeid prefix must match the regular expression [a-z]{0,63}';
                  end if;
                  return (prefix, uuid_generate_v7())::typeid;
                end
                ${'$'}${'$'}
                language plpgsql
                volatile;
                
                -- Function that generates a type_id of given type, and returns the parsed typeid as text.
                create or replace function typeid_generate_text(prefix text)
                returns text
                as ${'$'}${'$'}
                begin
                  if (prefix is null) or not (prefix ~ '^[a-z]{0,63}${'$'}') then
                    raise exception 'typeid prefix must match the regular expression [a-z]{0,63}';
                  end if;
                  return typeid_print((prefix, uuid_generate_v7())::typeid);
                end
                ${'$'}${'$'}
                language plpgsql
                volatile;
                
                -- Function that checks if a typeid is valid, for the given type prefix.
                create or replace function typeid_check(tid typeid, expected_type text)
                returns boolean
                as ${'$'}${'$'}
                declare
                  prefix text;
                begin
                  prefix = (tid).type;
                  return prefix = expected_type;
                end
                ${'$'}${'$'}
                language plpgsql
                immutable;
                
                -- Function that checks if a typeid is valid, for the given type_id in text format and type prefix, returns boolean.
                create or replace function typeid_check_text(typeid_str text, expected_type text)
                returns boolean
                as ${'$'}${'$'}
                declare
                  prefix text;
                  tid typeid;
                begin
                  tid = typeid_parse(typeid_str);
                  prefix = (tid).type;
                  return prefix = expected_type;
                end
                ${'$'}${'$'}
                language plpgsql
                immutable;
                
                -- Function that parses a string into a typeid.
                create or replace function typeid_parse(typeid_str text)
                returns typeid
                as ${'$'}${'$'}
                declare
                  prefix text;
                  suffix text;
                begin
                  if (typeid_str is null) then
                    return null;
                  end if;
                  if position('_' in typeid_str) = 0 then
                    return ('', base32_decode(typeid_str))::typeid;
                  end if;
                  prefix = split_part(typeid_str, '_', 1);
                  suffix = split_part(typeid_str, '_', 2);
                  if prefix is null or prefix = '' then
                    raise exception 'typeid prefix cannot be empty with a delimiter';
                  end if;
                  -- prefix must match the regular expression [a-z]{0,63}
                  if not prefix ~ '^[a-z]{0,63}${'$'}' then
                    raise exception 'typeid prefix must match the regular expression [a-z]{0,63}';
                  end if;
                
                  return (prefix, base32_decode(suffix))::typeid;
                end
                ${'$'}${'$'}
                language plpgsql
                immutable;
                
                -- Function that serializes a typeid into a string.
                create or replace function typeid_print(tid typeid)
                returns text
                as ${'$'}${'$'}
                declare
                  prefix text;
                  suffix text;
                begin
                  if (tid is null) then
                    return null;
                  end if;
                  prefix = (tid).type;
                  suffix = base32_encode((tid).uuid);
                  if (prefix is null) or not (prefix ~ '^[a-z]{0,63}${'$'}') then
                    raise exception 'typeid prefix must match the regular expression [a-z]{0,63}';
                  end if;
                  if prefix = '' then
                    return suffix;
                  end if;
                  return (prefix || '_' || suffix);
                end
                ${'$'}${'$'}
                language plpgsql
                immutable; 
                """.trimIndent(),
            )
        }

        transaction {
            exec(
                """
                
                -- Implementation of an equality operator that makes it easy to compare typeids stored
                -- as a compound tuple (prefix, uuid) against a typeid in text form.
                --
                -- This is useful so that clients can query using a textual representation of typeid.
                -- For example, using the users table in example.sql, you could write:
                --
                -- Query:
                -- SELECT * FROM users u WHERE u.id === 'user_01h455vb4pex5vsknk084sn02q'
                --
                -- Result:
                -- "(user,018962e7-3a6d-7290-b088-5c4e3bdf918c)",Ben Bitdiddle,ben@bitdiddle.com
                --
                -- Note: This also has the nice benefit of playing very well with generators
                -- such as Hibernate/JPA/JDBC/r2dbc, as you'll be able to do direct equality checks
                -- in repositories, such as for r2dbc:
                --
                -- @Query(value = "SELECT u.id, u.name, u.email FROM users u WHERE u.id === :id")
                -- Mono<UserEntity> findByPassedInTypeId(@Param("id") Mono<String> typeId); // user_01h455vb4pex5vsknk084sn02q
                --
                -- Note: This function only has to ever be declared once, and will work for any domains that use
                -- the original typeid type (e.g. this function gets called when querying for a user_id even though
                -- we never explicitly override the quality operator for a user_id.
                CREATE OR REPLACE FUNCTION typeid_eq_operator(lhs_id typeid, rhs_id VARCHAR)
                    RETURNS BOOLEAN AS ${'$'}${'$'}
                SELECT lhs_id = typeid_parse(rhs_id);
                ${'$'}${'$'} LANGUAGE SQL IMMUTABLE;

                CREATE OPERATOR === (
                    LEFTARG = typeid,
                    RIGHTARG = VARCHAR,
                    FUNCTION = typeid_eq_operator,
                    COMMUTATOR = ===,
                    NEGATOR = !==,
                    HASHES,
                    MERGES
                    );
                """.trimIndent(),
            )
        }
    }
}
