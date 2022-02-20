
from passlib.context import CryptContext

pwd_ctx=CryptContext(schemes=['bcrypt'],deprecated='auto')

class Hash:
    def bcrypt(password: str):#encrypting the password
        return pwd_ctx.hash(password)

    def verify(hashed_password,plain_password):#verified when user tries to log in
        return pwd_ctx.verify(hashed_password,plain_password)