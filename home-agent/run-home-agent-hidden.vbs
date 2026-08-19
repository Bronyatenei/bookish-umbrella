Option Explicit

Dim shell, fileSystem, scriptDirectory, command, exitCode

Set shell = CreateObject("WScript.Shell")
Set fileSystem = CreateObject("Scripting.FileSystemObject")
scriptDirectory = fileSystem.GetParentFolderName(WScript.ScriptFullName)
command = Chr(34) & fileSystem.BuildPath(scriptDirectory, "start-home-agent.cmd") & Chr(34)

' 0 = fully hidden window; True = wait so Task Scheduler tracks the agent lifetime.
exitCode = shell.Run(command, 0, True)
WScript.Quit exitCode
