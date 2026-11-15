package xraybridge

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"runtime/debug"
	"sync"
	"time"

	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	_ "github.com/xtls/xray-core/main/json"
)

var (
	mu          sync.Mutex
	instance    *core.Instance
	memoryLimit int64 = 256 << 20 // на Android памяти больше, чем в iOS-расширении
)

// На Android нет /etc/resolv.conf, и Go-резолвер по умолчанию стучится в 127.0.0.1:53.
// Приложение исключено из VPN, поэтому эти запросы идут напрямую. Нужно для доменных адресов серверов.
func init() {
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			return d.DialContext(ctx, network, "1.1.1.1:53")
		},
	}
}

// SetMemoryLimit — вызывается из Kotlin до Start(). mb <= 0 игнорируется.
func SetMemoryLimit(mb int32) {
	mu.Lock()
	defer mu.Unlock()
	if mb > 0 {
		memoryLimit = int64(mb) << 20
	}
}

// Start запускает Xray из JSON. assetDir — каталог с geoip.dat / geosite.dat.
func Start(config string, assetDir string) (err error) {
	mu.Lock()
	defer mu.Unlock()
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("xray panic: %v", r)
		}
	}()
	if instance != nil || sbInstance != nil {
		return errors.New("core already running")
	}

	debug.SetGCPercent(60)
	debug.SetMemoryLimit(memoryLimit)

	os.Setenv("XRAY_LOCATION_ASSET", assetDir)

	inst, e := core.StartInstance("json", []byte(config))
	if e != nil {
		return e
	}
	instance = inst
	return nil
}

// Stop останавливает ядро (xray или sing-box). TUN останавливается отдельно (StopTun).
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		_ = instance.Close()
		instance = nil
	}
	stopSingbox()
	debug.FreeOSMemory()
}

func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil || sbInstance != nil
}

func Version() string { return core.Version() }
