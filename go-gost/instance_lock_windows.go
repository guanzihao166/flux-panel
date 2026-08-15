//go:build windows

package main

import (
	"fmt"

	"golang.org/x/sys/windows"
)

type instanceLock struct {
	handle windows.Handle
}

func acquireInstanceLock(secret string) (*instanceLock, error) {
	name, err := windows.UTF16PtrFromString("Global\\FluxGost-" + lockKey(secret))
	if err != nil {
		return nil, err
	}
	handle, err := windows.CreateMutex(nil, true, name)
	if err != nil {
		return nil, err
	}
	if windows.GetLastError() == windows.ERROR_ALREADY_EXISTS {
		_ = windows.CloseHandle(handle)
		return nil, fmt.Errorf("同一节点 gost 已在运行")
	}
	return &instanceLock{handle: handle}, nil
}

func (l *instanceLock) release() {
	if l == nil || l.handle == 0 {
		return
	}
	_ = windows.ReleaseMutex(l.handle)
	_ = windows.CloseHandle(l.handle)
}
