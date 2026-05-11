from fastapi import FastAPI, Depends
from fastapi.testclient import TestClient

app = FastAPI()

class AdminUser(str):
    @property
    def is_super(self) -> bool:
        return getattr(self, "_is_super", False)
        
    @classmethod
    def create(cls, username, is_super):
        obj = cls(username)
        obj._is_super = is_super
        return obj

def verify_admin():
    return AdminUser.create("admin", is_super=True)

@app.get("/")
def read_root(username: str = Depends(verify_admin)):
    return {"is_super": getattr(username, "is_super", False), "type": str(type(username))}

client = TestClient(app)
print(client.get("/").json())
