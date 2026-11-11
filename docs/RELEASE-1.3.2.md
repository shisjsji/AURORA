# AURORA 1.3.2

1.3.1 修掉了限幅器签名和「写入失败留半配置效果器」这两件事，但两者都有残留：前者的反射失败仍然被静默吞掉，后者的泄漏路径只堵了一半。这一版把「失败」变成可以看见、可以上报的东西。

## 一、`open()` 真把效果器漏掉了

1.3.1 说写入失败时会先下线效果器。实际只有 `apply()` 里的失败会下线；`open()` 自己失败的两条路径——`hasControl` 为假、构造/反射抛异常——都直接 `return false`，效果器既没 `release()`，也没进引擎的 `effects` 表，于是 `releaseStale`/`releaseAll` 永远碰不到它。**会话上挂着一条只有 AOSP 默认 −2 dBFS 10:1 砖墙在工作的链，直到这个播放会话结束**——正是「写入失败却破音」的那条路。

现在 `open()` 的每条失败路径（解析不到类、session 非法、`hasControl` 拒绝、反射异常、段数为 0）统一走 `close()`，不留任何半配置对象。

## 二、反射写入的成败变成可上报状态

限幅器、低频压缩（MBC）、响度增强三处写入全是反射调用，全在 `catch (Throwable ignored)` 里。1.3.1 的 limiter 签名 bug 能藏两个大版本，就是因为这个。现在每一步都记一笔：

```
bands=6/6 limiter=ok mbc=ok loudness=ok verify=ok
```

- 写进新的 `Settings.Global` 键 `aurora_backend_stages`，并入 `report()` 的去重签名（不进签名的话，限幅器掉线不会触发任何写入，正好把问题藏起来）。
- 诊断页「处理链」区块按中文显示：频段 6/6、限幅器 失败（…）、回读 不一致（1.2dB）。
- 反馈表单随件带走 `stages` 字段。
- 任一步失败不再连带关掉整条链：AOSP 默认限幅器仍在保护喇叭，曲线比限幅器的形状更值钱。但现在你知道它没生效。

## 三、写入后回读校验

`setPreEqBand*` 返回成功、对象里却还是旧值的 OEM 实现是存在的——写入不抛异常，声音没变化。`apply()` 现在写入后逐段 `getPreEqBandByChannelIndex(0, i).getGain()` 读回，最大偏差超过 0.6 dB 记 `verify=mismatch:X.XdB`。反射不支持的环境跳过，不误报。

## 四、段数不足不再静默截断

DSP 只给 4 段时，原先 `min(bandCount, 6)` 只写前 4 段，界面仍显示 6 段全写入。现在记 `short=4/6`。

## 五、修掉「写失败但 applied 仍为 true」

`apply()` 失败的三条路径（逐段写入、`setEnabled`、尾部异常）都清掉 `applied` 与 `signature`，下个 tick 会重写，而不是认为已经写好。

## 六、会话效果器存活检查

`isOpen()` 原先没有调用方。现在 tick 里发现表里的效果器已失去控制（音轨被拆但没收到回调）会关掉重建，而不是每 2 秒报同一条错直到进程死掉。

## 七、拖滑块即时生效

`ContentObserver` 漏了 `aurora_spread`、`aurora_loudness`、`aurora_geq_saved` 三个键，拖动声场/响度滑块要等最多 2 秒的定时 tick 才生效。已补上。同一处顺带补齐 `aurora_device_pack`（此前已在文档里声称监听）。

## 八、设置不再半提交

`AuroraState.update()` 原来把 3–7 条 `settings put` 用一个 `&&` 串在一条 su 命令里，中途断就留半状态，而 `echo AURORA_OK` 只保证最后一行执行到了。现在：

- 逐条执行，任一步失败立即停下并指名是哪一步（`preset` / `strength` / `curve` / `saved` / `enabled`），报「保存失败（curve 这一步）」而不是笼统的失败。
- 开关写最后。shell 中途道死时，机器要么关着，要么开着但曲线完整——不会出现「开着、指向新预设、曲线是上周的」。
- su 根本没起来才提示 Root 权限，超时不再冒充权限问题。

## 九、文档与测试

- README 与 CONTEXT 里「每个新会话延迟一拍再挂效果器」的说法与实现不符：`registerAudioPlaybackCallback` 在音轨建立后才回调，拿到会话即 `open()`。实现是对的，文档已改。
- 把 `parsePack` 的频点/增益上限/限幅器校验、`buildGains` 的增益合成、`releaseStale` 的过期判定抽成静态纯函数，补了 38 个用例（原来只有 8 个）。
- 新增 `.github/workflows/build.yml`：每次 push 跑单元测试。
