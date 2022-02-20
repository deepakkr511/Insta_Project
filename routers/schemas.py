from datetime import datetime
from pydantic import BaseModel
from typing import List

class UserBase(BaseModel):
    username:str
    email:str
    password:str

class UserDisplay(BaseModel):#details returned to user through api
    username :str
    email:str
    class Config():#for conversion from orm to json
        orm_mode=True

class User(BaseModel):
    username: str
    class Config():#for conversion from orm to json
        orm_mode=True

#fro postdisplay
class Comment(BaseModel):
    text:str
    username:str
    timestamp:datetime
    class Config():#for conversion from orm to json
        orm_mode=True
    

class PostBase(BaseModel):
    image_url:str
    image_url_type:str
    caption: str
    creator_id:int

class PostDisplay(BaseModel):
    id:int
    image_url:str
    image_url_type:str
    timestamp:datetime
    caption:str
    user:User
    comments:List[Comment]
    class Config():#for conversion from orm to json
        orm_mode=True

class UserAuth(BaseModel):
    id:int
    username:str
    email:str

class CommentBase(BaseModel):
    username: str
    text:str
    post_id:int