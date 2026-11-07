INSERT INTO T_PROPERTIES(`KEY`, `VALUE`, SOURCE, ORIGIN, IS_ACTIVE) VALUES ('${data.key}', '${data.value?string}', ${data.source}, '${data.origin!""}', ${data.active?c});
