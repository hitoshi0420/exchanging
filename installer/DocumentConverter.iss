[Setup]
AppId={{D3F67C3E-8A66-4E6E-8A16-A8AA18B285C0}
AppName=本地文档格式转换工具
AppVersion=1.0.0
AppPublisher=Local
DefaultDirName={autopf}\DocumentConverter
DefaultGroupName=本地文档格式转换工具
DisableProgramGroupPage=yes
OutputDir=.
OutputBaseFilename=DocumentConverter-Setup-1.0.0
Compression=lzma
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务:"; Flags: unchecked

[Dirs]
Name: "{app}\logs"

[Files]
Source: "..\start.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\target\document-converter-1.0.0.jar"; DestDir: "{app}"; DestName: "document-converter-1.0.0.jar"; Flags: ignoreversion
Source: "..\jdk\*"; DestDir: "{app}\jdk"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\本地文档格式转换工具"; Filename: "{app}\start.bat"; WorkingDir: "{app}"
Name: "{commondesktop}\本地文档格式转换工具"; Filename: "{app}\start.bat"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\start.bat"; Description: "启动 本地文档格式转换工具"; Flags: nowait postinstall skipifsilent
