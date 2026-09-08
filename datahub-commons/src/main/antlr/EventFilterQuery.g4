/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * The events filter expression language: what a caller may put in `advancedFilter`.
 *
 * PostgreSQL dialect first, the way DuckDB, CockroachDB and Materialize chose. ClickHouse
 * spelling never appears here; the function registry translates on the way out.
 *
 * This grammar IS the security boundary. Anything it cannot express — SELECT, subqueries,
 * UNION, JOIN, CASE, arithmetic, string concatenation, regex operators, ANY/ALL — needs no
 * rejecting downstream, because no parse tree for it exists. Add to this file only with that
 * in mind: every production is a promise the renderer has to keep safely.
 */
grammar EventFilterQuery;

// A leading WHERE is optional and ignored. People type it, and refusing it is a papercut
// with nothing behind it.
statement   : WHERE? expr EOF ;

expr        : orExpr ;
orExpr      : andExpr (OR andExpr)* ;
andExpr     : notExpr (AND notExpr)* ;
notExpr     : NOT notExpr                                       # notNode
            | primary                                           # primaryNode
            ;
primary     : LPAREN expr RPAREN                                # groupNode
            | predicate                                         # predicateNode
            ;

predicate   : operand comparisonOp operand                      # comparisonPredicate
            | operand NOT? LIKE operand                         # likePredicate
            | operand NOT? ILIKE operand                        # ilikePredicate
            | operand NOT? IN LPAREN literal (COMMA literal)* RPAREN  # inPredicate
            | operand NOT? BETWEEN literal AND literal          # betweenPredicate
            | operand IS NOT? NULL                              # isNullPredicate
            // A boolean-valued operand standing on its own, e.g. has_key('site'). Last, so the
            // shapes above win where both could match.
            | operand                                           # booleanPredicate
            ;

comparisonOp : EQ | NEQ | LT | LTE | GT | GTE ;

// `::` is Postgres cast sugar. It resolves to the same converter the to_*() functions do, so
// there is one code path and one set of rules for both spellings.
operand     : atom (CAST_OP typeName)? ;

atom        : metadataRef                                       # metadataAtom
            | functionCall                                      # functionAtom
            | identifier                                        # columnAtom
            | literal                                           # literalAtom
            ;

// metadata['key'] — the key is a string literal, never an expression, so it can always be
// bound as a parameter.
metadataRef  : METADATA LBRACKET STRING RBRACKET ;

functionCall : identifier LPAREN (operand (COMMA operand)*)? RPAREN ;

// Double-quoted identifiers are accepted and folded like bare ones. Nothing here has a name
// that needs quoting, and copying Postgres's case-sensitivity rule for them would buy
// confusion rather than compatibility.
identifier   : IDENT | QUOTED_IDENT ;

typeName     : IDENT ;

literal      : STRING | NUMBER | TRUE | FALSE ;

// --- keywords, case-insensitive ---
WHERE   : [Ww][Hh][Ee][Rr][Ee] ;
AND     : [Aa][Nn][Dd] ;
OR      : [Oo][Rr] ;
NOT     : [Nn][Oo][Tt] ;
LIKE    : [Ll][Ii][Kk][Ee] ;
ILIKE   : [Ii][Ll][Ii][Kk][Ee] ;
IN      : [Ii][Nn] ;
BETWEEN : [Bb][Ee][Tt][Ww][Ee][Ee][Nn] ;
IS      : [Ii][Ss] ;
NULL    : [Nn][Uu][Ll][Ll] ;
METADATA: [Mm][Ee][Tt][Aa][Dd][Aa][Tt][Aa] ;
TRUE    : [Tt][Rr][Uu][Ee] ;
FALSE   : [Ff][Aa][Ll][Ss][Ee] ;

// Reserved rather than merely absent, so `having count() > 1` says "not supported yet"
// instead of "unknown identifier". See the deferral note in the plan: aggregates over a
// ReplacingMergeTree with no FINAL count un-merged duplicates, which has to be answered
// before HAVING can mean anything.
SELECT  : [Ss][Ee][Ll][Ee][Cc][Tt] ;
FROM    : [Ff][Rr][Oo][Mm] ;
GROUP   : [Gg][Rr][Oo][Uu][Pp] ;
HAVING  : [Hh][Aa][Vv][Ii][Nn][Gg] ;
EXISTS  : [Ee][Xx][Ii][Ss][Tt][Ss] ;
UNION   : [Uu][Nn][Ii][Oo][Nn] ;
JOIN    : [Jj][Oo][Ii][Nn] ;

// --- operators and punctuation ---
CAST_OP : '::' ;
NEQ     : '!=' | '<>' ;
LTE     : '<=' ;
GTE     : '>=' ;
EQ      : '=' ;
LT      : '<' ;
GT      : '>' ;
LPAREN  : '(' ;
RPAREN  : ')' ;
LBRACKET: '[' ;
RBRACKET: ']' ;
COMMA   : ',' ;

// --- literals ---
// Single-quoted with '' escaping, as Postgres. No E'' escapes and no dollar quoting: they
// buy nothing here and each is another way for a value to carry a surprise.
STRING       : '\'' ( ~'\'' | '\'\'' )* '\'' ;
NUMBER       : '-'? [0-9]+ ('.' [0-9]+)? ;
IDENT        : [a-zA-Z_] [a-zA-Z_0-9]* ;
QUOTED_IDENT : '"' ~["]+ '"' ;

// Comments are safe here in a way they are not in a string-concatenating query builder: the
// emitted SQL is re-rendered from the AST, so a comment cannot survive into it.
LINE_COMMENT  : '--' ~[\r\n]* -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN) ;
WS            : [ \t\r\n]+ -> channel(HIDDEN) ;
