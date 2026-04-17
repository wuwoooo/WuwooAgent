import re

def filter_file(path):
    with open(path, 'r') as f:
        lines = f.readlines()
        
    new_lines = []
    in_enhanced_func = False
    
    for line in lines:
        if 'private fun createEnhancedBitmap' in line or 'private fun createTitleEnhancedBitmap' in line:
            in_enhanced_func = True
            continue
            
        if in_enhanced_func:
            if line.startswith('    }'):
                in_enhanced_func = False
            continue
            
        if 'enhanced' in line.lower() and 'crop' in line.lower():
            continue
            
        new_lines.append(line)
        
    with open(path, 'w') as f:
        f.writelines(new_lines)

filter_file('app/src/main/kotlin/com/agentime/ime/host/capture/CaptureImageProcessor.kt')
filter_file('app/src/main/kotlin/com/agentime/ime/host/capture/CaptureController.kt')
filter_file('app/src/main/kotlin/com/agentime/ime/host/orchestrator/HostForegroundService.kt')
print("Done")
