' legado Reader for Windows - silent launcher (no console window).
' Double-click this file; same as legado-win.cmd but without the console.
Option Explicit
Dim shell, fso, here, ps1, cmd
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
here = fso.GetParentFolderName(WScript.ScriptFullName)
ps1 = here & "\launcher.ps1"
cmd = "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & ps1 & """"
' 0 = hidden window, False = do not wait
shell.Run cmd, 0, False
