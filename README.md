# YuppyAI

**Сайт и панель: [silentiumdlc.space](https://silentiumdlc.space/)** · [Релизы](https://github.com/gobgolaxi/YuppyAI/releases)

Античит для Paper, который оценивает аим нейросетью, а не фиксированными порогами.
Плагин режет бой на окна, считает по ним признаки и отправляет их в сервис, а тот
возвращает вероятность чита. Из вероятностей копится буфер: по одному порогу
уходит алерт, по другому — наказание.

## Установка

1. Paper (или форк) 1.16.5+ и ProtocolLib.
2. Положить jar и ProtocolLib в `plugins/`, запустить сервер.
3. В `plugins/YuppyAI/config.yml` указать `api.url`.
4. Написать `/yai connect` на сервере и открыть выданную ссылку под своим аккаунтом —
   ключ и IP сервера пропишутся сами. Аккаунт и бесплатный Pro на 3 дня —
   на [silentiumdlc.space](https://silentiumdlc.space/).
5. `/yai guide` в игре — короткая инструкция.

Ключ можно вписать и руками в `api.key`, тогда IP сервера нужно указать в панели самому:
без адреса ключ не работает.

## Команды

| Команда | Что делает |
|---|---|
| `/yai connect` | привязать сервер к аккаунту одной ссылкой |
| `/yai guide` | как пользоваться античитом, в один экран |
| `/yai monitor` | живой список подозрительных |
| `/yai prob <ник>` | следить за одним игроком |
| `/yai history <ник>` | прошлые показания, хранятся на диске |
| `/yai journal` | журнал сработок |
| `/yai player <ник>` | буфер и история игрока |
| `/yai forget <ник>` | сбросить подозрение |
| `/yai vanish` | скрытое наблюдение |
| `/yai npc` | манекен-спарринг для проверки |
| `/yai data` | запись обучающих данных |
| `/yai diag [ник]` | почему окна игрока анализируются или нет |
| `/yai dashboard` | меню настроек |
| `/yai reload` | перечитать конфиг |

Права: `yuppyai.command`, `yuppyai.alerts`, `yuppyai.journal`, `yuppyai.vanish`,
`yuppyai.data`, `yuppyai.npc`, `yuppyai.connect`, `yuppyai.diag`, `yuppyai.bypass`.

## Сборка

```
./gradlew shadowJar -PmcVersion=1.16.5
./gradlew shadowJar -PmcVersion=1.21.11
```

Версии 1.16.x собираются в байткод Java 16 — Commodore на этих серверах не читает
ничего новее. Версии 1.21.x собираются под Java 21. Версионный код лежит в
`src/v116` и `src/v121`, общий — в `src/main`.

## Ссылки

- Панель и тарифы — [silentiumdlc.space](https://silentiumdlc.space/)
- Состояние сервиса — [silentiumdlc.space/stats](https://silentiumdlc.space/stats)
- Документация — [silentiumdlc.space/guide](https://silentiumdlc.space/guide)
