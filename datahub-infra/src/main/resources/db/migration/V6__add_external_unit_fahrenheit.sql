
-- Fahrenheit
INSERT INTO unit(name, description, external_id, external_id_hash, long_name, symbol,
                 quantity, source, source_reference, conversion_multiplier, conversion_offset)
VALUES(
       'DEG_F',
       'The unit fahrenheit, is an imperial unit for temperature.',
       'temperature_deg_f',
       8585149709781086567,
       'degree Fahrenheit',
       '°F',
       'Temperature',
       'qudt.org',
       'https://qudt.org/vocab/unit/DEG_F',
       0.5555555555555556,
       459.67
);
INSERT INTO unit_alias_names(unit_id, alias_names_text)
    SELECT id, '℉' FROM unit WHERE external_id_hash = 8585149709781086567
UNION ALL
    SELECT id, 'F' FROM unit WHERE external_id_hash = 8585149709781086567
UNION ALL
    SELECT id, 'deg F' FROM unit WHERE external_id_hash = 8585149709781086567
UNION ALL
    SELECT id, 'degF' FROM unit WHERE external_id_hash = 8585149709781086567
UNION ALL
    SELECT id, 'DegF' FROM unit WHERE external_id_hash = 8585149709781086567
UNION ALL
    SELECT id, 'FAH' FROM unit WHERE external_id_hash = 8585149709781086567
;