# Idempotency — biblioteca de uso geral (core + Spring Boot starter)

Garante que uma operação execute **no máximo uma vez** por chave de
idempotência, devolvendo o resultado da primeira execução nas chamadas
repetidas. Funciona em endpoints REST, consumidores de fila, jobs agendados e
em qualquer código Java — o motor não depende de Spring.

```
idempotency-library
├── idempotency-bom                  # alinha a versão de todos os módulos
├── idempotency-core                 # API, motor e SPI. Zero dependências.
├── idempotency-spring-boot-starter  # AOP + auto-configuração + store em memória
├── idempotency-store-jdbc           # store JDBC + migration Flyway (PostgreSQL)
├── idempotency-store-redis          # store Redis
└── idempotency-store-mongo          # store MongoDB
```

| Módulo | O que traz |
|---|---|
| `idempotency-bom` | opcional: só `dependencyManagement`, para quem declara mais de um módulo |
| `idempotency-core` | `@Idempotent`, `@IdempotentKey`, `IdempotencyEngine`, SPI `IdempotencyStore`/`ResultSerializer`, store em memória |
| `idempotency-spring-boot-starter` | aspecto para `@Idempotent`, resolvedores de chave, serializer Jackson, handler HTTP e store em memória |
| `idempotency-store-jdbc` / `-redis` / `-mongo` | uma store por módulo, com auto-configuração e propriedades `idempotency.<backend>.*` próprias; trazem o starter e o driver do backend |

## Instalação

**Uma dependência**, a da store que você usa. Ela traz o starter e o driver do
backend:

```xml
<dependency>
    <groupId>com.example.idempotency</groupId>
    <artifactId>idempotency-store-jdbc</artifactId> <!-- ou -redis / -mongo -->
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Essa mesma dependência atende endpoints REST, listeners de fila, jobs e
services no mesmo app: o `@Idempotent` funciona em qualquer bean Spring, e só a
origem da chave muda (header HTTP ou o próprio dado). Não existe módulo "web" ou
"messaging" separado.

```java
@RestController
class PedidoController {
    @PostMapping("/pedidos")
    @Idempotent                                   // chave: header Idempotency-Key
    ResponseEntity<Pedido> criar(@RequestBody CriarPedido req) { ... }
}

@Component
class PedidoListener {
    @KafkaListener(topics = "pedidos")
    @Idempotent(key = "#msg.pedidoId")            // chave: campo da mensagem
    void consumir(PedidoMessage msg) { ... }
}
```

Nenhuma propriedade é obrigatória: o módulo de store no classpath é usado sozinho.
Sem nenhum módulo, a lib cai na store em memória, que não deduplica entre
instâncias. `idempotency.store` só é necessária para desempatar quando houver
mais de um módulo de store. Se faltar o módulo da store configurada, o startup
falha dizendo qual dependência adicionar.

Para usar só a store em memória (testes, protótipos), declare
`idempotency-spring-boot-starter` no lugar do módulo de store.

### BOM (opcional)

Só vale a pena quando o app declara mais de um módulo da lib (por exemplo
`idempotency-core` direto, ou duas stores). Ele mantém todos na mesma versão e
dispensa o `<version>` em cada um:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.example.idempotency</groupId>
            <artifactId>idempotency-bom</artifactId>
            <version>0.1.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Versionamento

A versão vive em um só lugar, a propriedade `revision` do `pom.xml` raiz; todos
os módulos a herdam e o `flatten-maven-plugin` a resolve nos POMs publicados.
Para liberar: `mvn -Drevision=1.2.0 deploy`.

## Uso em 30 segundos

```java
@PostMapping("/pedidos")
@Idempotent(ttl = "PT24H")
public ResponseEntity<PedidoResponse> criar(@RequestBody CriarPedidoRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.criar(request));
}
```

```bash
curl -X POST http://localhost:8080/pedidos \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 8f14e45f-ceea-467e-bd44-9a3e42db2555' \
  -d '{"produto":"livro","quantidade":2}'
```

Repetir a chamada devolve o **mesmo** `201` com o **mesmo** corpo, sem executar
a lógica de negócio de novo.

```yaml
idempotency:
  default-ttl: PT24H   # opcional; a store vem do módulo no classpath
```

## Fora de HTTP: fila, job, service

A chave vem do próprio dado, não de um header:

```java
@JmsListener(destination = "pedidos")
@Idempotent(ttl = "PT1H")
public void consumir(@IdempotentKey String pedidoId, PedidoMessage mensagem) { ... }

@KafkaListener(topics = "pedidos")
@Idempotent(key = "#mensagem.dedupId", ttl = "PT1H")
public void consumirComExpressao(PedidoMessage mensagem) { ... }
```

Não é preciso nenhuma configuração extra: o aspecto intercepta qualquer bean
gerenciado pelo Spring. **Use um identificador de negócio** (id do pedido, id
da transação, `MessageDeduplicationId` do SQS FIFO). Não use o `MessageId` do
SQS — ele muda a cada envio, então uma duplicata do produtor passaria direto.

## Origem da chave (precedência)

| Ordem | Fonte | Quando usar |
|---|---|---|
| 1 | `@Idempotent(key = "#pedido.id")` — SpEL sobre os argumentos | chave derivada de vários campos |
| 2 | parâmetro `@IdempotentKey` (aceita caminho: `@IdempotentKey("header.id")`) | filas, jobs, services |
| 3 | header HTTP `Idempotency-Key` (nome configurável) | APIs REST |

Nenhuma delas resolveu → `MissingIdempotencyKeyException` (400 em HTTP).

**Escopo.** Toda chave vive dentro de um escopo, e a unicidade é sobre o par
`(chave, escopo)`. O default é `pacote.Classe#metodo` — estável e igual dentro
e fora de HTTP. Use `@Idempotent(scope = "...")` para compartilhar o escopo
entre dois pontos de entrada da mesma operação (por exemplo o endpoint REST e o
consumidor de fila que fazem a mesma coisa).

## Anotação declarada na interface

Funciona: tanto `@Idempotent` quanto `@IdempotentKey` podem ficar só no
contrato, e a implementação não precisa repeti-los.

```java
public interface PaymentGateway {
    @Idempotent(ttl = "PT5M")
    Receipt charge(@IdempotentKey String transactionId, Money amount);
}

@Service
class StripePaymentGateway implements PaymentGateway {
    @Override
    public Receipt charge(String transactionId, Money amount) { ... }   // sem anotação
}
```

Java não herda anotações de método, e o pointcut `@annotation(...)` do Spring
AOP só enxerga o que está fisicamente no método da classe alvo — por isso a
biblioteca usa um *advisor* próprio (`IdempotencyAdvisor`), que percorre a
hierarquia do método (implementação, interfaces e superclasses). É o mesmo
motivo pelo qual `@Transactional` funciona em interface.

Duas ressalvas, iguais às de qualquer proxy Spring: a chamada tem que passar
pelo bean (auto-invocação `this.charge(...)` não é interceptada), e o escopo
default nomeia a **classe concreta** — duas implementações da mesma interface
têm escopos distintos. Se as duas devem compartilhar a janela de deduplicação,
declare `@Idempotent(scope = "pagamentos#cobrar")`.

## Opções de `@Idempotent`

| Atributo | Default | Efeito |
|---|---|---|
| `key` | `""` | expressão SpEL da chave |
| `scope` | `""` | escopo; vazio = `Classe#metodo` |
| `ttl` | property `idempotency.default-ttl` (24h) | janela de deduplicação, ISO-8601 |
| `replay` | `true` | grava e devolve o resultado; `false` só deduplica (retorna `null` na repetição) |
| `waitForCompletion` | `""` | espera a execução concorrente terminar (ISO-8601); vazio = falha rápida |
| `fingerprintPayload` | `true` | rejeita a mesma chave com payload diferente |

## O que acontece em cada cenário

| Situação | Resultado | HTTP |
|---|---|---|
| Chave nova | executa e grava o resultado | o da sua lógica |
| Mesma chave, mesmo payload, já concluída | replay do resultado gravado | o original |
| Mesma chave, payload diferente | `IdempotencyKeyConflictException` | `422` |
| Execução concorrente em andamento | `IdempotencyInProgressException` (ou espera, com `waitForCompletion`) | `409` |
| Sem chave | `MissingIdempotencyKeyException` | `400` |
| Lógica de negócio lança exceção | chave é liberada; retry legítimo reexecuta | a sua exceção |

Em fila nada disso vira status HTTP: as exceções sobem para o listener, e é
isso que faz a mensagem voltar para a fila. Calibre o *visibility timeout* para
ser maior que a operação (senão a redelivery encontra a chave em andamento e
entra em laço) e tenha DLQ configurada.

## Backends

| Backend | Atomicidade da reserva | Expiração | Observação |
|---|---|---|---|
| `memory` | `ConcurrentHashMap.compute` | lazy, na leitura | **não** deduplica entre instâncias; usada quando não há módulo de store, avisa no log |
| `jdbc` | chave primária `(idempotency_key, scope)` | job de purge (`idempotency.jdbc.cleanup-enabled`) | mais durável; migration Flyway em `db/idempotency/postgresql` (idempotency-store-jdbc) |
| `redis` | `SET key value NX PX ttl` | TTL nativo, exato | mais rápido; durabilidade depende de AOF/RDB |
| `mongo` | índice único `(key, scope)` | TTL index + filtro de leitura | monitor de TTL do Mongo roda a cada ~60s |

Por que **JDBC e não JPA**: uma `@Entity` declarada dentro de uma biblioteca
não entra no `@EntityScan` da aplicação, o que obrigaria todo consumidor a
listar o pacote da lib. O SQL usado aqui é portável e dispensa isso — é a mesma
escolha que Spring Session JDBC e Spring Batch fazem. O `idempotency-store-jdbc`
publica a migration Flyway `V202611091200__idempotency_record.sql` (somente
PostgreSQL) em `classpath:db/idempotency/postgresql`. Se a aplicação usa Flyway,
esse location é adicionado sozinho ao Flyway dela (o vendor é detectado pela URL
do datasource; quem já o lista em `spring.flyway.locations` não ganha duplicata).
Sem Flyway, ou com `idempotency.jdbc.flyway-enabled=false`, copie o SQL.

A versão é um timestamp (`V<yyyyMMddHHmm>`) para não colidir com as migrações da
aplicação nem de outras bibliotecas (ex.: `db/outbox/postgresql`) no mesmo histórico
do Flyway. Use timestamp também nas migrações da aplicação e ligue
`spring.flyway.out-of-order=true`. O nome da tabela, `idempotency_record`, é fixo.

**Owner token.** Cada reserva guarda o token de quem a fez (`owner`), e
`complete`/`release` só valem para esse dono, de forma atômica: `WHERE owner = ?`
no JDBC, filtro no Mongo e script Lua no Redis. Um chamador lento, cuja
reserva expirou e foi assumida por outro, não apaga nem sobrescreve o registro
do novo dono. `complete` também aceita uma nova expiração, para separar o TTL
curto da reserva do TTL longo do registro concluído.

Atualizando uma tabela existente:
`ALTER TABLE idempotency_record ADD COLUMN owner VARCHAR(64);`. Registros
antigos ficam com `owner` nulo e não podem ser concluídos nem liberados; eles
apenas expiram.

## Configuração

| Property | Default | Descrição |
|---|---|---|
| `idempotency.store` | — (detectada) | `memory` \| `jdbc` \| `redis` \| `mongo`; obrigatória só com mais de um módulo de store |
| `idempotency.default-ttl` | `PT24H` | TTL quando a anotação não define |
| `idempotency.header-name` | `Idempotency-Key` | header lido em requisições HTTP |
| `idempotency.poll-interval` | `PT0.05S` | intervalo de polling do `waitForCompletion` |
| `idempotency.web-exception-handler` | `true` | mapeia as exceções para 400/409/422 |
| `idempotency.jdbc.flyway-enabled` | `true` | adiciona `classpath:db/idempotency/<vendor>` ao Flyway da aplicação |
| `idempotency.jdbc.cleanup-enabled` | `true` | job de limpeza de registros expirados |
| `idempotency.jdbc.cleanup-interval` | `PT15M` | intervalo do job |
| `idempotency.jdbc.transaction-mode` | `requires-new` | `requires-new`: escritas em transação própria. `join`: escritas entram na transação de quem chama (reserva num savepoint), confirmando junto com o negócio. `join` exige transaction manager com savepoints, como o `DataSourceTransactionManager` |
| `idempotency.redis.key-prefix` | `idempotency:` | prefixo das chaves |
| `idempotency.mongo.collection` | `idempotency_record` | coleção usada |

## Sem Spring

O core é autossuficiente:

```java
IdempotencyEngine engine = new IdempotencyEngine(
        new InMemoryIdempotencyStore(),   // ou sua implementação de IdempotencyStore
        new JdkResultSerializer());

IdempotencyContext contexto = IdempotencyContext.of(
        new IdempotencyKeyId(pedidoId, "pagamentos#cobrar"), Duration.ofHours(24));

Recibo recibo = engine.supply(contexto, () -> gateway.cobrar(pedido));
```

Para plugar outro backend, implemente `IdempotencyStore` — a única garantia
exigida é que `reserve` seja atômico. Herde
`AbstractIdempotencyStoreContractTest` (publicado no `test-jar` do core) e a
sua implementação já vem com os testes de comportamento prontos.

## Limitações conhecidas (leia antes de usar em pagamento)

- **Janela entre executar e marcar.** O resultado é gravado *depois* que a
  lógica retorna, em uma operação separada. Se o processo morrer exatamente
  nesse intervalo, a chave fica reservada até o TTL expirar. Para operações
  críticas, chame o `IdempotencyEngine` dentro da própria transação de negócio
  (store JDBC), em vez de depender do aspecto, e mantenha deduplicação também
  no downstream (repassando a mesma chave ao gateway).
- **Replay de `ResponseEntity`** preserva status e corpo, não headers
  arbitrários.
- **Fingerprint** exige argumentos serializáveis por Jackson; se não forem, use
  `fingerprintPayload = false`.
- **Tipos genéricos** no retorno (`List<Pedido>`) perdem o tipo do elemento no
  replay — o tipo gravado é a classe concreta do resultado.
- **A store em memória** não deduplica entre instâncias. Ela é usada quando não
  há módulo de store no classpath, para que um teste local funcione sem
  infraestrutura, e loga um aviso no startup. Em produção, confira que o módulo
  `idempotency-store-*` está nas dependências de runtime.

## Build e testes

```bash
mvn clean install                  # compila + testes unitários
mvn verify                         # + testes de integração (requer Docker)
mvn -pl idempotency-core test      # só o core
```

Os testes de integração sobem PostgreSQL, Redis e MongoDB com Testcontainers e
rodam o mesmo contrato de store em cada backend.
