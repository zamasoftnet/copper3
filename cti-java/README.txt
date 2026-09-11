CTI Java版
バージョン @version@

Javaを使ってCopper PDFにアクセスするためのプログラムです。
Copper PDF 2.1.0以降が必要です。
使用方法は付属のAPIドキュメント、サンプルプログラム、以下のオンラインマニュアルを参照してください。
http://dl.cssj.jp/docs/copper/3.2/html/3420_ctip2_java.html

ソースコードはGitHubで公開しています。
https://github.com/mimidesunya/copper3/tree/main/cti-java

■ 付属物

cti-driver-@version@.jar		-- ドライバ本体（CTIP, REST, CLIが利用可能）
cti-driver-min-@version@.jar	-- 最小構成のドライバ（REST, CLIは利用不可）
apidoc			-- APIドキュメント(Javadoc)
lib      			-- サンプルのコンパイルに必要なライブラリ
examples 			-- サンプルプログラム
compile-examples.sh		-- サンプルプログラムをコンパイルするスクリプト(Linux)
compile-examples.bat		-- サンプルプログラムをコンパイルするスクリプト(Windows)
copper             		-- コマンドラインcopperプログラム(Linux)
copper.bat              		-- コマンドラインcopperプログラム(Windows)

■　コマンドラインプログラムについて
copper, copper.batについては、以下のドキュメントを参照して下さい。
http://dl.cssj.jp/docs/copper/3.2/html/2100_tools.html#admin-copper

■ サンプルプログラムについて
javacコマンドが実行できるようにパスを設定しておいて下さい。
Linuxではcompile-examples.sh、Windowsではcompile-examples.batを実行するとコンパイルされます。

サンプルプログラムを実行するには、コンパイル後に以下のコマンドを実行してください。

Linux
java -cp cti-driver-@version@.jar:classes クラス名

Windows
java -cp cti-driver-@version@.jar;classes クラス名

Servlet/JSPのサンプル実行する場合は、examples/webappをサーブレットコンテナに配備して、
以下のアドレスをブラウザで表示してください。
Tomcat 10 では examples/webapp/WEB-INF/web.xml を examples/webapp/WEB-INF/web-jakarta.xml で置き換えてください。

(Filterのテスト)
http://ホスト:ポート/コンテキスト/source.jsp
(Servletのテスト)
http://ホスト:ポート/コンテキスト/pdf/source.jsp

以下のファイルはAntのサンプルファイルです。実行にはAntが必要です。
examples/build.xml

■ ライセンス

Copyright (c) 2012-2024 座間ソフト

Apache License Version 2.0に基づいてライセンスされます。
あなたがこのファイルを使用するためには、本ライセンスに従わなければなりません。
本ライセンスのコピーは下記の場所から入手できます。

   http://www.apache.org/licenses/LICENSE-2.0

適用される法律または書面での同意によって命じられない限り、
本ライセンスに基づいて頒布されるソフトウェアは、明示黙示を問わず、
いかなる保証も条件もなしに「現状のまま」頒布されます。
本ライセンスでの権利と制限を規定した文言については、本ライセンスを参照してください。 

Copyright (c) 2012-2024 Zamasoft.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

■ 変更履歴
-- v2.2.4 2026-09-11
TLS 1.3、部分送受信、終了・中断・reset時の資源解放を修正しました。
CTIP/HTTPS REST は既定で証明書と接続先名を検証します。
独自CAは JVM の javax.net.ssl.trustStore へ登録してください。
Antの変換用 property はJVMのシステムプロパティとは別物です。
試験用に -Djp.cssj.driver.tls.insecure=true または CLI --insecure を指定できます。
旧 jp.cssj.driver.tls.trust は指定時にJVMごとに1回警告します。新名が優先します。
-t/--trust も同じ意味で使えます。--insecure との同時指定は新名を優先し1回警告します。
insecure=true のCTIPは証明書検証を省略します。RESTは提示チェーンが1枚なら信頼扱いとし、
2枚以上は通常の信頼検証を行います。両者ともホスト名検証を省略します。
ctips と version=1 の組合せは、平文へ接続せず例外にします。
文字列長は65535バイトまで送受信し、それ以上はIOExceptionにします。
reset後の旧出力ストリームは無効です(writeはIllegalStateException、closeは何もしません)。

版と公開APIの対応:
2.2.4 = 従来の MetaSource / RandomBuilder / META-INF/plugin 探索 + TLS修正
2.3.0 = 上流の SourceMetadata / FragmentedOutput に移行した別API + TLS修正

-- v2.2.3 2024-03-27
サーブレットでContent-Typeを出力する際に、空の charset= パラメータが追加されてしまうバグに対応しました。

-- v2.2.2 2024-03-14
javax.servlet 4 に対応しました。
jakarta.servlet 5 に対応しました。

-- v2.2.1 2023-04-04
Javaの以下のバグに起因するメモリリークが起こっていたため、回避する措置をしました。
https://bugs.openjdk.org/browse/JDK-4872014

-- v2.2.0 2018-04-26
Closableを利用可能になる等Java 8のコーディングに対応しました。

-- v2.1.5 2017-02-19
http:プロトコルが使用できないバグを修正しました。

-- v2.1.4 2014-04-14
ctip:プロトコルでタイムアウトを設定できるようになりました。
以下のようにURLパラメータで、timeoutをミリ秒単位で指定できます。
ctip://hostname/?timeout=10000
ctips, http等他のプロトコルではタイムアウトは無効です。

-- v2.1.3 2013-04-04
ドライバ付属のCommons HTTP ClientでHTTP通信を行った時に、
NoClassDefFoundError: org/apache/commons/logging/LogFactory
が生ずるバグを修正しました。

Antのサンプルを加えました。

copperコマンドのシェルスクリプト、batファイルを加えました。

-- v2.1.2 2012-09-14
Apache系のライブラリに依存しない、最小構成のjarを配布するようにしました。

以下のバグを修正しました
multipart/formdataによりRESTインターフェースにアクセスした時にrest.xxxパラメータが無視される

-- v2.1.1 2011-04-11
以下のバグを修正しました
Servlet/JSPの出力をPDFに変換する場合、元のデータのサイズが大きいとCTIHttpServletResponseWrapperでエラーが発生する。

-- v2.1.0 2011-03-03
Copper PDF 3 以降からサポートする、複数の文書から１つのPDFを生成する機能に対応。
TLS通信に対応。

以下のバグを修正しました
CTISession.setSourceResolver を使用するとき、リソースが見つからない場合に処理が中断することがある。

-- v2.0.2 2010-03-16
以下のバグを修正しました
CTISession.resourceメソッド中で例外が発生した場合に不正なリクエストを送る
変換が中断された場合にIllegalBlockModeExceptionが発生することがある
CTISession.transcodeを実行すると、setResultsによる結果の設定がされていない状態になる。

-- v2.0.1 2009-12-10
以下のバグを修正しました
メッセージの引数(args)がメッセージを受け取る度に増え、正しく取得できない(CTIP 2.0)。
複数パス生成で最初のパスでサーバー側エラーで処理が中断された場合、NullPointerExceptionが発生する(CTIP 2.0)。
CTIMessageHelper.STDOUT, CTIMessageHelper.STDERRは非推奨としました。

-- v2.0.0 2009-08-02
最初のリリース。
